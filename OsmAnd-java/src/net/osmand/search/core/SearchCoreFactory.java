package net.osmand.search.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;

import net.osmand.CollatorStringMatcher.StringMatcherMode;
import net.osmand.LocationConvert;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapAddressReaderAdapter;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.SearchPoiTypeFilter;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.data.Amenity;
import net.osmand.data.Building;
import net.osmand.data.City;
import net.osmand.data.City.CityType;
import net.osmand.data.LatLon;
import net.osmand.data.MapObject;
import net.osmand.data.QuadRect;
import net.osmand.data.QuadTree;
import net.osmand.data.Street;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.osm.PoiFilter;
import net.osmand.osm.PoiType;
import net.osmand.search.SearchUICore.SearchResultMatcher;
import net.osmand.search.core.SearchPhrase.NameStringMatcher;
import net.osmand.search.core.SearchPhrase.SearchPhraseDataType;
import net.osmand.util.Algorithms;
import net.osmand.util.GeoPointParserUtil;
import net.osmand.util.GeoPointParserUtil.GeoParsedPoint;
import net.osmand.util.MapUtils;

import com.jwetherell.openmap.common.LatLonPoint;
import com.jwetherell.openmap.common.UTMPoint;


public class SearchCoreFactory {
	// TODO tests for geo:, location (+)
	// TODO add UTM support (+)	
	// TODO location 43°38′33.24″N 79°23′13.7″W (+)
	// TODO partial location
	
	// TODO add full text search with comma correct order
	// TODO MED add full text search without comma and different word order
	// TODO MED edit in the middle (with words and comma)?
	
	// TODO exclude duplicate streets/cities/pois...
	
	// TODO UI support poi additional select type and search
	// TODO UI display results momentarily
	// TODO UI automatically increase radius if nothing found (log radius search)
	
	
	//////////////// CONSTANTS //////////
	public static final int SEARCH_REGION_API_PRIORITY = 3;
	public static final int SEARCH_REGION_OBJECT_PRIORITY = 10;
	
	// context less
	public static final int SEARCH_LOCATION_PRIORITY = 0;
	public static final int SEARCH_AMENITY_TYPE_PRIORITY = 1;
	public static final int SEARCH_AMENITY_TYPE_API_PRIORITY = 1;
	
	// context	
	public static final int SEARCH_STREET_BY_CITY_PRIORITY = 2;
	public static final int SEARCH_BUILDING_BY_CITY_PRIORITY = 3;
	public static final int SEARCH_BUILDING_BY_STREET_PRIORITY = 1;
	public static final int SEARCH_AMENITY_BY_TYPE_PRIORITY = 3;
	
	// context less (slow)
	public static final int SEARCH_ADDRESS_BY_NAME_API_PRIORITY = 5;
	public static final int SEARCH_ADDRESS_BY_NAME_API_PRIORITY_RADIUS2 = 5;
	public static final int SEARCH_ADDRESS_BY_NAME_PRIORITY = 5; 
	public static final int SEARCH_ADDRESS_BY_NAME_PRIORITY_RADIUS2 = 5;
	// context less (slower)	
	public static final int SEARCH_AMENITY_BY_NAME_PRIORITY = 7;
	public static final int SEARCH_AMENITY_BY_NAME_API_PRIORITY_IF_POI_TYPE = 7;
	public static final int SEARCH_AMENITY_BY_NAME_API_PRIORITY_IF_3_CHAR = 7;
	
	public static abstract class SearchBaseAPI implements SearchCoreAPI {
		@Override
		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
			return true;
		}
		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			return 1;
		}
	}
	
	
	public static class SearchRegionByNameAPI extends SearchBaseAPI {

		

		@Override
		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
			for (BinaryMapIndexReader bmir : phrase.getOfflineIndexes()) {
				if (bmir.getRegionCenter() != null) {
					SearchResult sr = new SearchResult(phrase);
					sr.localeName = bmir.getRegionName();
					sr.object = bmir;
					sr.file = bmir;
					sr.priority = SEARCH_REGION_OBJECT_PRIORITY;
					sr.objectType = ObjectType.REGION;
					sr.location = bmir.getRegionCenter();
					sr.preferredZoom = 6;
					if (phrase.getLastWord().length() <= 1 && phrase.isNoSelectedType()) {
						resultMatcher.publish(sr);
					} else if (phrase.getNameStringMatcher().matches(sr.localeName)) {
						resultMatcher.publish(sr);
					}
				}
			}
			return true;
		}
		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if(!p.isNoSelectedType()) {
				return -1;
			}
			return SEARCH_REGION_API_PRIORITY;
		}
	}
	
	public static class SearchAddressByNameAPI extends SearchBaseAPI {
		
		private static final int DEFAULT_ADDRESS_BBOX_RADIUS = 1000*1000;
		private static final int LIMIT = 10000;
		
		
		private Map<BinaryMapIndexReader, List<City>> townCities = new LinkedHashMap<>();
		private QuadTree<City> townCitiesQR = new QuadTree<City>(new QuadRect(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE),
				8, 0.55f);
		private List<City> resArray = new ArrayList<>();

		@Override
		public int getSearchPriority(SearchPhrase p) {
			if (!p.isNoSelectedType() && p.getRadiusLevel() == 1) {
				return -1;
			}
			if (p.isNoSelectedType()) {
				return SEARCH_ADDRESS_BY_NAME_API_PRIORITY;
			}
			return SEARCH_ADDRESS_BY_NAME_API_PRIORITY_RADIUS2;
		}

		@Override
		public boolean search(final SearchPhrase phrase, final SearchResultMatcher resultMatcher) throws IOException {
			if (phrase.getLastWord().isEmpty()) {
				return false;
			}
			// phrase.isLastWord(ObjectType.CITY, ObjectType.VILLAGE, ObjectType.POSTCODE) || phrase.isLastWord(ObjectType.REGION)
			if (phrase.isNoSelectedType() || phrase.getRadiusLevel() >= 2) {
				initAndSearchCities(phrase, resultMatcher);
				searchByName(phrase, resultMatcher);
			}
			return true;
		}


		private void initAndSearchCities(final SearchPhrase phrase, final SearchResultMatcher resultMatcher) throws IOException {
			QuadRect bbox = phrase.getRadiusBBoxToSearch(DEFAULT_ADDRESS_BBOX_RADIUS * 10);
			Iterator<BinaryMapIndexReader> offlineIndexes = phrase.getOfflineIndexes(bbox, SearchPhraseDataType.ADDRESS);
			while(offlineIndexes.hasNext()) {
				BinaryMapIndexReader r = offlineIndexes.next();
				if(!townCities.containsKey(r)) {
					BinaryMapIndexReader.buildAddressRequest(null);
					List<City> l = r.getCities(null, BinaryMapAddressReaderAdapter.CITY_TOWN_TYPE);
					townCities.put(r, l);
					for(City c  : l) {
						LatLon cl = c.getLocation();
						c.setReferenceFile(r);
						int y = MapUtils.get31TileNumberY(cl.getLatitude());
						int x = MapUtils.get31TileNumberX(cl.getLongitude());
						QuadRect qr = new QuadRect(x, y, x, y);
						townCitiesQR.insert(c, qr);
					}
				}
			}
			if (phrase.isNoSelectedType() && bbox != null && phrase.getLastWord().length() > 0) {
				NameStringMatcher nm = phrase.getNameStringMatcher();
				resArray.clear();
				resArray = townCitiesQR.queryInBox(bbox, resArray);
				int limit = 0;
				for (City c : resArray) {
					SearchResult sr = new SearchResult(phrase);
					sr.object = c;
					sr.file = (BinaryMapIndexReader) c.getReferenceFile();
					sr.localeName = c.getName(phrase.getSettings().getLang(), true);
					sr.otherNames = c.getAllNames(true);
					sr.localeRelatedObjectName = sr.file.getRegionName();
					sr.location = c.getLocation();
					sr.priority = 1;
					sr.priorityDistance = 0.1;
					sr.objectType = ObjectType.CITY;
					if(nm.matches(sr.localeName) || nm.matches(sr.otherNames)) {
						resultMatcher.publish(sr);
					}
					if(limit++ > LIMIT * phrase.getRadiusLevel()) {
						break;
					}
				}
			}
		}


		private void searchByName(final SearchPhrase phrase, final SearchResultMatcher resultMatcher)
				throws IOException {
			if(phrase.getRadiusLevel() > 1 || phrase.getLastWord().length() > 3) {
				final boolean locSpecified = phrase.getLastTokenLocation() != null;
				LatLon loc = phrase.getLastTokenLocation();
				final QuadRect streetBbox = phrase.getRadiusBBoxToSearch(DEFAULT_ADDRESS_BBOX_RADIUS);
				final QuadRect postcodeBbox = phrase.getRadiusBBoxToSearch(DEFAULT_ADDRESS_BBOX_RADIUS * 5);
				final QuadRect villagesBbox = phrase.getRadiusBBoxToSearch(DEFAULT_ADDRESS_BBOX_RADIUS * 3);
				final QuadRect cityBbox = phrase.getRadiusBBoxToSearch(DEFAULT_ADDRESS_BBOX_RADIUS * 10);
				final int priority = phrase.isNoSelectedType() ? 
						SEARCH_ADDRESS_BY_NAME_PRIORITY : SEARCH_ADDRESS_BY_NAME_PRIORITY_RADIUS2;
				final BinaryMapIndexReader[] currentFile = new BinaryMapIndexReader[1]; 
				ResultMatcher<MapObject> rm = new ResultMatcher<MapObject>() {
					int limit = 0;
					@Override
					public boolean publish(MapObject object) {
						if(isCancelled()) {
							return false;
						}
						SearchResult sr = new SearchResult(phrase);
						sr.object = object;
						sr.file = currentFile[0];
						sr.localeName = object.getName(phrase.getSettings().getLang(), true);
						sr.otherNames = object.getAllNames(true);
						sr.localeRelatedObjectName = sr.file.getRegionName();
						sr.location = object.getLocation();
						sr.priorityDistance = 1;
						sr.priority = priority;
						int y = MapUtils.get31TileNumberY(object.getLocation().getLatitude());
						int x = MapUtils.get31TileNumberX(object.getLocation().getLongitude());
						List<City> closestCities = null;
						if (object instanceof Street) {
							if(locSpecified && !streetBbox.contains(x, y, x, y)) {
								return false;
							}
							if(object.getName().startsWith("<")) {
								return false;
							}
							sr.objectType = ObjectType.STREET;
							sr.localeRelatedObjectName = ((Street)object).getCity().getName(phrase.getSettings().getLang(), true);
						} else if (object instanceof City) {
							CityType type = ((City)object).getType();
							if (type == CityType.CITY || type == CityType.TOWN) {
								if(phrase.isNoSelectedType()) {
									// ignore city/town
									return false;
								}
								if (locSpecified && !cityBbox.contains(x, y, x, y)) {
									return false;
								}
								
								sr.objectType = ObjectType.CITY;
								sr.priorityDistance = 0.1;
							} else if (((City)object).isPostcode()) {
								if (locSpecified && !postcodeBbox.contains(x, y, x, y)) {
									return false;
								}
								sr.objectType = ObjectType.POSTCODE;
							}  else {
								if (locSpecified && !villagesBbox.contains(x, y, x, y)) {
									return false;
								}
								City c = null;
								if(closestCities == null) {
									closestCities = townCitiesQR.queryInBox(villagesBbox, new ArrayList<City>());
								}
								double minDist = -1;
								double pDist = -1;
								for(City s : closestCities) {
									double ll = MapUtils.getDistance(s.getLocation(), object.getLocation());
									double pd = s.getType() == CityType.CITY ? ll : ll * 10;
									if(minDist == -1 || pd < pDist) {
										c = s;
										minDist = ll;
										pDist = pd ;
									}
								}
								if(c != null) {
									sr.localeRelatedObjectName = c.getName(phrase.getSettings().getLang(), true);
									sr.distRelatedObjectName = minDist; 
								}
								sr.objectType = ObjectType.VILLAGE;
							}
						} else {
							return false;
						}
						limit ++;
						resultMatcher.publish(sr);
						return false;
					}

					@Override
					public boolean isCancelled() {
						return limit > LIMIT * phrase.getRadiusLevel() || 
								resultMatcher.isCancelled();
					}
				};
				Iterator<BinaryMapIndexReader> offlineIterator = phrase.getRadiusOfflineIndexes(DEFAULT_ADDRESS_BBOX_RADIUS * 10, 
						SearchPhraseDataType.ADDRESS);
				while (offlineIterator.hasNext()) {
					BinaryMapIndexReader r = offlineIterator.next();
					currentFile[0] = r;
					SearchRequest<MapObject> req = BinaryMapIndexReader.buildAddressByNameRequest(rm, phrase
							.getLastWord().toLowerCase(), StringMatcherMode.CHECK_STARTS_FROM_SPACE);
					if (locSpecified) {
						req.setBBoxRadius(loc.getLatitude(), loc.getLongitude(), phrase.getRadiusSearch(DEFAULT_ADDRESS_BBOX_RADIUS * 10));
					}
					r.searchAddressDataByName(req);
				}
			}
		}


	}
	
	public static class SearchAmenityByNameAPI extends SearchBaseAPI {
		private static final int LIMIT = 10000;
		private static final int BBOX_RADIUS = 1000 * 1000;
		
		@Override
		public boolean search(final SearchPhrase phrase, final SearchResultMatcher resultMatcher) throws IOException {
			if(phrase.getLastWord().length() == 0) {
				return false;
			}
			final BinaryMapIndexReader[] currentFile = new BinaryMapIndexReader[1];
			Iterator<BinaryMapIndexReader> offlineIterator = phrase.getRadiusOfflineIndexes(BBOX_RADIUS, 
					SearchPhraseDataType.POI);
			QuadRect bbox = phrase.getRadiusBBoxToSearch(BBOX_RADIUS);
			SearchRequest<Amenity> req = BinaryMapIndexReader.buildSearchPoiRequest(
					(int)bbox.centerX(), (int)bbox.centerY(),
					phrase.getLastWord(), 
					(int)bbox.left, (int)bbox.right, 
					(int)bbox.top, (int)bbox.bottom,
					new ResultMatcher<Amenity>() {
						int limit = 0;
						@Override
						public boolean publish(Amenity object) {
							if(limit ++ > LIMIT) {
								return false;
							}
							SearchResult sr = new SearchResult(phrase);
							sr.otherNames = object.getAllNames(true);
							sr.localeName = object.getName(phrase.getSettings().getLang(), true);
							sr.object = object;
							sr.preferredZoom = 17;
							sr.file = currentFile[0];
							sr.location = object.getLocation();
							sr.priority = SEARCH_AMENITY_BY_NAME_PRIORITY;
							sr.priorityDistance = 1;
							sr.objectType = ObjectType.POI;
							
							resultMatcher.publish(sr);
							return false;
						}

						@Override
						public boolean isCancelled() {
							return resultMatcher.isCancelled() && (limit < LIMIT) ;
						}
					});
			while (offlineIterator.hasNext()) {
				BinaryMapIndexReader r = offlineIterator.next();
				currentFile[0] = r;
				r.searchPoiByName(req);
			}
			return true;
		}
		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if(p.hasObjectType(ObjectType.POI) || 
					p.getLastWord().length() == 0) {
				return -1;
			}
			if(p.hasObjectType(ObjectType.POI_TYPE)) {
				if(p.getRadiusLevel() > 1) {
					return SEARCH_AMENITY_BY_NAME_API_PRIORITY_IF_POI_TYPE;
				} else {
					return -1;
				}
			}
			if(p.getLastWord().length() > 3 || p.getRadiusLevel() > 1) {
				return SEARCH_AMENITY_BY_NAME_API_PRIORITY_IF_3_CHAR;
			}
			return -1;
		}
	}
	
	
	public static class SearchAmenityTypesAPI extends SearchBaseAPI {

		private Map<String, PoiType> translatedNames;
		private List<PoiFilter> topVisibleFilters;

		public SearchAmenityTypesAPI(MapPoiTypes types) {
			translatedNames = types.getAllTranslatedNames(false);
			topVisibleFilters = types.getTopVisibleFilters();
		}
		
		@Override
		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
//			results.clear();
			TreeSet<AbstractPoiType> results = new TreeSet<>(new Comparator<AbstractPoiType>() {

				@Override
				public int compare(AbstractPoiType o1, AbstractPoiType o2) {
					return o1.getKeyName().compareTo(o2.getKeyName());
				}
			});
			NameStringMatcher nm = phrase.getNameStringMatcher();
			for (PoiFilter pf : topVisibleFilters) {
				if (Algorithms.isEmpty(phrase.getLastWord()) || nm.matches(pf.getTranslation())) {
					results.add(pf);
				}
			}
			if (!Algorithms.isEmpty(phrase.getLastWord())) {
				Iterator<Entry<String, PoiType>> it = translatedNames.entrySet().iterator();
				while (it.hasNext()) {
					Entry<String, PoiType> e = it.next();
					if (nm.matches(e.getKey())) {
						results.add(e.getValue());
					}
				}
			}
			for (AbstractPoiType p : results) {
				SearchResult res = new SearchResult(phrase);
				res.localeName = p.getTranslation();
				res.object = p;
				res.priority = SEARCH_AMENITY_TYPE_PRIORITY;
				res.priorityDistance = 0;
				res.objectType = ObjectType.POI_TYPE;
				resultMatcher.publish(res);
			}
			return true;
		}
		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if (p.hasObjectType(ObjectType.POI) || p.hasObjectType(ObjectType.POI_TYPE)) {
				return -1;
			}
			return SEARCH_AMENITY_TYPE_API_PRIORITY;
		}
	}
	
	public static class SearchAmenityByTypeAPI extends SearchBaseAPI {
		
		
		private MapPoiTypes types;

		public SearchAmenityByTypeAPI(MapPoiTypes types) {
			this.types = types;
		}
		public boolean isLastWordPoi(SearchPhrase p ) {
			return p.isLastWord(ObjectType.POI_TYPE);
		}
		

		private Map<PoiCategory, LinkedHashSet<String>> acceptedTypes = new LinkedHashMap<PoiCategory,
				LinkedHashSet<String>>();
		private Map<String, PoiType> poiAdditionals = new HashMap<String, PoiType>();
		public void updateTypesToAccept(AbstractPoiType pt) {
			pt.putTypes(acceptedTypes);
			if (pt instanceof PoiType && ((PoiType) pt).isAdditional() && ((PoiType) pt).getParentType() != null) {
				fillPoiAdditionals(((PoiType) pt).getParentType());
			} else {
				fillPoiAdditionals(pt);
			}
		}

		private void fillPoiAdditionals(AbstractPoiType pt) {
			for (PoiType add : pt.getPoiAdditionals()) {
				poiAdditionals.put(add.getKeyName().replace('_', ':').replace(' ', ':'), add);
				poiAdditionals.put(add.getTranslation().replace(' ', ':').toLowerCase(), add);
			}
			if (pt instanceof PoiFilter && !(pt instanceof PoiCategory)) {
				for (PoiType ps : ((PoiFilter) pt).getPoiTypes()) {
					fillPoiAdditionals(ps);
				}
			}
		}
		
		@Override
		public boolean search(final SearchPhrase phrase, final SearchResultMatcher resultMatcher) throws IOException {
			if(phrase.isLastWord(ObjectType.POI_TYPE)) {
				final AbstractPoiType pt = (AbstractPoiType) phrase.getLastSelectedWord().getResult().object;
				acceptedTypes.clear();
				poiAdditionals.clear();
				updateTypesToAccept(pt);
				
				QuadRect bbox = phrase.getRadiusBBoxToSearch(10000);
				List<BinaryMapIndexReader> oo = phrase.getOfflineIndexes();
				final BinaryMapIndexReader[] selected = new BinaryMapIndexReader[1];
				final NameStringMatcher ns = phrase.getNameStringMatcher();

				SearchRequest<Amenity> req = BinaryMapIndexReader.buildSearchPoiRequest(
						(int)bbox.left, (int)bbox.right, 
						(int)bbox.top, (int)bbox.bottom, -1,
						new SearchPoiTypeFilter() {
							
							@Override
							public boolean isEmpty() {
								return false;
							}
							
							@Override
							public boolean accept(PoiCategory type, String subtype) {
								if (type == null) {
									return true;
								}
								if (!types.isRegisteredType(type)) {
									type = types.getOtherPoiCategory();
								}
								if (!acceptedTypes.containsKey(type)) {
									return false;
								}
								LinkedHashSet<String> set = acceptedTypes.get(type);
								if (set == null) {
									return true;
								}
								return set.contains(subtype);
							}
						}, new ResultMatcher<Amenity>() {

							@Override
							public boolean publish(Amenity object) {
								SearchResult res = new SearchResult(phrase);
								res.localeName = object.getName(phrase.getSettings().getLang(), true);
								res.otherNames = object.getAllNames(true);
								if (!Algorithms.isEmpty(phrase.getLastWord())
										&& !(ns.matches(res.localeName) || ns.matches(res.otherNames))) {
									return false;
								}
								if (Algorithms.isEmpty(res.localeName)) {
									AbstractPoiType st = types.getAnyPoiTypeByKey(object.getSubType());
									if (st != null) {
										res.localeName = st.getTranslation();
									} else {
										res.localeName = object.getSubType();
									}
								}
								res.object = object;
								res.preferredZoom = 17;
								res.file = selected[0];
								res.location = object.getLocation();
								res.priority = SEARCH_AMENITY_BY_TYPE_PRIORITY;
								res.priorityDistance = 1;
								res.objectType = ObjectType.POI;
								resultMatcher.publish(res);
								return false;
							}

							@Override
							public boolean isCancelled() {
								return resultMatcher.isCancelled();
							}
						});
				for (BinaryMapIndexReader o : oo) {
					selected[0] = o;
					o.searchPoi(req);
				}
			}
			return true;
		}
	
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if(p.isLastWord(ObjectType.POI_TYPE) && 
					p.getLastTokenLocation() != null) {
				return SEARCH_AMENITY_BY_TYPE_PRIORITY;
			}
			return -1;
		}
	}
	
	
	
	public static class SearchStreetByCityAPI extends SearchBaseAPI {
		
		private static int LIMIT = 10000;
		@Override
		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
			SearchWord sw = phrase.getLastSelectedWord();
			if (isLastWordCityGroup(phrase) && sw.getResult() != null && sw.getResult().file != null) {
				City c = (City) sw.getResult().object;
				if (c.getStreets().isEmpty()) {
					sw.getResult().file.preloadStreets(c, null);
				}
				int limit = 0;
				NameStringMatcher nm = phrase.getNameStringMatcher();
				for (Street object : c.getStreets()) {

					SearchResult res = new SearchResult(phrase);
					res.localeName = object.getName(phrase.getSettings().getLang(), true);
					res.otherNames = object.getAllNames(true);
					if(object.getName().startsWith("<")) {
						// streets related to city
						continue;
					}
					if (!Algorithms.isEmpty(phrase.getLastWord())
							&& !(nm.matches(res.localeName) || nm.matches(res.otherNames))) {
						continue;
					}
					res.localeRelatedObjectName = c.getName(phrase.getSettings().getLang(), true);
					res.object = object;
					res.preferredZoom = 17;
					res.file = sw.getResult().file;
					res.location = object.getLocation();
					res.priority = SEARCH_STREET_BY_CITY_PRIORITY;
					//res.priorityDistance = 1;
					res.objectType = ObjectType.STREET;
					if (limit++ > LIMIT) {
						break;
					}
					resultMatcher.publish(res);
				}
				return true;
			}
			return true;
		}

		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if(isLastWordCityGroup(p)) {
				return SEARCH_STREET_BY_CITY_PRIORITY;
			}
			return -1;
		}
		
	}
	
	public static boolean isLastWordCityGroup(SearchPhrase p ) {
		return p.isLastWord(ObjectType.CITY) || p.isLastWord(ObjectType.POSTCODE) || 
				p.isLastWord(ObjectType.VILLAGE);
	}	
	
	public static class SearchBuildingAndIntersectionsByStreetAPI extends SearchBaseAPI {
		Street cacheBuilding;
		
		@Override
		public boolean search(SearchPhrase phrase, final SearchResultMatcher resultMatcher) throws IOException {
			Street s = null;
			int priority = SEARCH_BUILDING_BY_STREET_PRIORITY;
			if(phrase.isLastWord(ObjectType.STREET)) {
				s =  (Street) phrase.getLastSelectedWord().getResult().object;
			}
			if(isLastWordCityGroup(phrase)) {
				priority = SEARCH_BUILDING_BY_CITY_PRIORITY;
				Object o = phrase.getLastSelectedWord().getResult().object;
				if(o instanceof City) {
					List<Street> streets = ((City) o).getStreets();
					if(streets.size() == 1) {
						s = streets.get(0);
					} else {
						for(Street st : streets) {
							if(st.getName().equals(((City) o).getName()) ||
									st.getName().equals("<"+((City) o).getName()+">")) {
								s = st;
								break;	
							}
						}
					}
				}
			}
			
			if(s != null) {
				BinaryMapIndexReader file = phrase.getLastSelectedWord().getResult().file;
				String lw = phrase.getLastWord();
				NameStringMatcher sm = phrase.getNameStringMatcher();
				if (cacheBuilding != s) {
					cacheBuilding = s;
					SearchRequest<Building> sr = BinaryMapIndexReader
							.buildAddressRequest(new ResultMatcher<Building>() {

								@Override
								public boolean publish(Building object) {
									return true;
								}

								@Override
								public boolean isCancelled() {
									return resultMatcher.isCancelled();
								}
							});
					file.preloadBuildings(s, sr);
					Collections.sort(s.getBuildings(), new Comparator<Building>() {

						@Override
						public int compare(Building o1, Building o2) {
							int i1 = Algorithms.extractFirstIntegerNumber(o1.getName());
							int i2 = Algorithms.extractFirstIntegerNumber(o2.getName());
							if (i1 == i2) {
								return 0;
							}
							return Algorithms.compare(i1, i2);
						}
					});
				}
				for(Building b : s.getBuildings()) {
					SearchResult res = new SearchResult(phrase);
					boolean interpolation = b.belongsToInterpolation(lw);
					if(!sm.matches(b.getName()) && !interpolation) {
						continue;
					}
					
					res.localeName = b.getName(phrase.getSettings().getLang(), true);
					res.otherNames = b.getAllNames(true);
					res.object = b;
					res.file = file;
					res.priority = priority;
					res.priorityDistance = 0;
					res.objectType = ObjectType.HOUSE;
					if(interpolation) {
						res.location = b.getLocation(b.interpolation(lw));
					} else {
						res.location = b.getLocation();
					}
					res.preferredZoom = 17;
					resultMatcher.publish(res);
				}
				if(!Algorithms.isEmpty(lw) && !Character.isDigit(lw.charAt(0))) {
					for(Street street : s.getIntersectedStreets()) {
						SearchResult res = new SearchResult(phrase);
						if(!sm.matches(street.getName()) && !sm.matches(street.getAllNames(true))) {
							continue;
						}
						res.otherNames = street.getAllNames(true);
						res.localeName = street.getName(phrase.getSettings().getLang(), true);
						res.object = street;
						res.file = file;
						res.priorityDistance = 0;
						res.objectType = ObjectType.STREET_INTERSECTION;
						res.location = street.getLocation();
						res.preferredZoom = 16;
						resultMatcher.publish(res);
					}
				}
				
				
				
			}
			return true;
		}

		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			if(isLastWordCityGroup(p)) {
				return SEARCH_BUILDING_BY_CITY_PRIORITY;
			}
			if(!p.isLastWord(ObjectType.STREET)) {
				return -1;
			}
			return SEARCH_BUILDING_BY_STREET_PRIORITY;
		}
	}
	
	
	
	
	public static class SearchLocationAndUrlAPI extends SearchBaseAPI {
		

//		newFormat = PointDescription.FORMAT_DEGREES;
//		newFormat = PointDescription.FORMAT_MINUTES;
//		newFormat = PointDescription.FORMAT_SECONDS;
		public void testUTM() {
			double northing = 0;
			double easting = 0;
			String zone = "";
			char c = zone.charAt(zone.length() -1);
			int z = Integer.parseInt(zone.substring(0, zone.length() - 1));
			UTMPoint upoint = new UTMPoint(northing, easting, z, c);
			LatLonPoint ll = upoint.toLatLonPoint();
			LatLon loc = new LatLon(ll.getLatitude(), ll.getLongitude());
		}
		
		@Override
		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
			if(phrase.getLastWord().length() == 0) {
				return false;
			}
			boolean parseUrl = parseUrl(phrase, resultMatcher);
			if(!parseUrl) {
				parseLocation2(phrase, resultMatcher);
			}
			return super.search(phrase, resultMatcher);
		}
		private boolean isKindOfNumber(String s) {
			for(int i = 0; i < s.length(); i ++) {
				char c = s.charAt(i);
				if(c >= '0' && c <= '9') {
				} else if(c == ':' || c == '.' || c == '#' || c == ',' || c == '-' || c == '\'' || c == '"') {
				} else {
					return false;
				}
			}
			return true;
		}
		
		LatLon parseLocation(String s) {
			s = s.trim();
			if(s.length() == 0 || !(s.charAt(0) == '-' || Character.isDigit(s.charAt(0))
					 || s.charAt(0) == 'S' || s.charAt(0) == 's' 
					 || s.charAt(0) == 'N' || s.charAt(0) == 'n' 
					 || s.contains("://"))) {
				return null;
			}
			List<Double> d = new ArrayList<>();
			List<Object> all = new ArrayList<>();
			List<String> strings = new ArrayList<>();
			splitObjects(s, d, all, strings);
			if(d.size() == 0) {
				return null;
			}
			// detect UTM
			if (all.size() == 4 && d.size() == 3 && all.get(1) instanceof String) {
				char ch = all.get(1).toString().charAt(0);
				if (Character.isLetter(ch)) {
					UTMPoint upoint = new UTMPoint(d.get(2), d.get(1), d.get(0).intValue(), ch);
					LatLonPoint ll = upoint.toLatLonPoint();
					return new LatLon(ll.getLatitude(), ll.getLongitude());
				}
			}

			if (all.size() == 3 && d.size() == 2 && all.get(1) instanceof String) {
				char ch = all.get(1).toString().charAt(0);
				String combined = strings.get(2);
				if (Character.isLetter(ch)) {
					try {
						String east = combined.substring(0, combined.length() / 2);
						String north = combined.substring(combined.length() / 2, combined.length());
						UTMPoint upoint = new UTMPoint(Double.parseDouble(north), Double.parseDouble(east), d.get(0)
								.intValue(), ch);
						LatLonPoint ll = upoint.toLatLonPoint();
						return new LatLon(ll.getLatitude(), ll.getLongitude());
					} catch (NumberFormatException e) {
					}
				}
			}
			// try to find split lat/lon position
			int jointNumbers = 0;
			int lastJoin = 0;
			int degSplit = -1;
			int degType = -1; // 0 - degree, 1 - minutes, 2 - seconds
			boolean finishDegSplit = false;
			int northSplit = -1;
			int eastSplit = -1;
			for(int i = 1; i < all.size(); i++ ) {
				if(all.get(i - 1) instanceof Double && all.get(i) instanceof Double) {
					jointNumbers ++;
					lastJoin = i;
				}
				if(all.get(i).equals("n") || all.get(i).equals("s") || 
						all.get(i).equals("N") || all.get(i).equals("S")) {
					northSplit = i + 1;
				}
				if(all.get(i).equals("e") || all.get(i).equals("w") || 
						all.get(i).equals("E") || all.get(i).equals("W")) {
					eastSplit = i;
				}
				int dg = -1;
				if (all.get(i).equals("°")) {
					dg = 0;
				} else if (all.get(i).equals("\'") || all.get(i).equals("′")) {
					dg = 1;
				} else if (all.get(i).equals("″") || all.get(i).equals("\"")) {
					dg = 2;
				}
				if (dg != -1) {
					if (!finishDegSplit) {
						if (degType < dg) {
							degSplit = i + 1;
							degType = dg;
						} else {
							finishDegSplit = true;
							degType = dg;
						}
					} else {
						if (degType < dg) {
							degType = dg;
						} else {
							// reject delimiter
							degSplit = -1;
						}
					}
				}
			}
			int split = -1;
			if(jointNumbers == 1) {
				split = lastJoin;
			}
			if(northSplit != -1 && northSplit < all.size() -1) {
				split = northSplit;
			} else if(eastSplit != -1 && eastSplit < all.size() -1) {
				split = eastSplit;
			} else if(degSplit != -1 && degSplit < all.size() -1) {
				split = degSplit;
			}
			
			if(split != -1) {
				double lat = parse1Coordinate(all, 0, split);
				double lon = parse1Coordinate(all, split, all.size());
				return new LatLon(lat, lon);
			}
			if(d.size() == 2) {
				return new LatLon(d.get(0), d.get(1));
			}
			// simple url case
			if (s.contains("://")) {
				double lat = 0;
				double lon = 0;
				boolean only2decimals = true;
				for (int i = 0; i < d.size(); i++) {
					if (d.get(i).doubleValue() != d.get(i).intValue()) {
						if (lat == 0) {
							lat = d.get(i);
						} else if (lon == 0) {
							lon = d.get(i);
						} else {
							only2decimals = false;
						}
					}
				}
				if (lat != 0 && lon != 0 && only2decimals) {
					return new LatLon(lat, lon);
				}
			}
			// split by equal number of digits
			if (d.size() > 2 && d.size() % 2 == 0) {
				int ind = d.size() / 2 + 1;
				int splitEq = -1;
				for (int i = 0; i < all.size(); i++) {
					if(all.get(i) instanceof Double) {
						ind --;
					}
					if(ind == 0) {
						splitEq = i;
						break;
					}
				}
				if (splitEq != -1) {
					double lat = parse1Coordinate(all, 0, splitEq);
					double lon = parse1Coordinate(all, splitEq, all.size());
					return new LatLon(lat, lon);
				}
			}
			return null;
			
		}
		
		public double parse1Coordinate(List<Object> all, int begin, int end) {
			boolean neg = false;
			double d = 0;
			int type = 0; // degree - 0, minutes - 1, seconds = 2
			Double prevDouble = null;
			for(int i = begin; i <= end; i++) {
				Object o = i == end ? "" : all.get(i);
				if(o.equals("S") || o.equals("W"))  {
					neg = !neg;
				}
				if (prevDouble != null) {
					if(o.equals("°")) {
						type = 0;
					} else if(o.equals("′") /*o.equals("'")*/) {
						// ' can be used as delimeter ignore it
						type = 1;
					} else if(o.equals("\"") || o.equals("″")) {
						type = 2;
					}
					if (type == 0) {
						double ld = prevDouble.doubleValue();
						if(ld < 0) {
							ld = -ld;
							neg = true;
						}
						d += ld;
					} else if (type == 1) {
						d += prevDouble.doubleValue() / 60.f;
					} else /*if (type == 1) */ {
						d += prevDouble.doubleValue() / 3600.f;
					}
					type++;
				}
				if(o instanceof Double) {
					prevDouble = (Double) o;
				} else {
					prevDouble = null;
				}
			}
			if(neg) {
				d = -d;
			}
			return d;
		}

		private void splitObjects(String s, List<Double> d, List<Object> all, List<String> strings) {
			boolean digit = false;
			int word = -1;
			for(int i = 0; i <= s.length(); i++) {
				char ch = i == s.length() ? ' ' : s.charAt(i);
				boolean dg = Character.isDigit(ch);
				boolean nonwh = ch != ',' && ch != ' ' && ch != ';';
				if (ch == '.' || dg || ch == '-' ) {
					if(!digit) {
						if(word != -1) {
							all.add(s.substring(word, i));
							strings.add(s.substring(word, i));
						}
						digit = true;
						word = i;
					} else {
						if(word == -1) {
							word = i;
						}
						// if digit
						// continue
					}
				} else {
					if(digit){
						try {
							double dl = Double.parseDouble(s.substring(word, i));
							d.add(dl);
							all.add(dl);
							strings.add(s.substring(word, i));
							digit = false;
							word = -1;
						} catch (NumberFormatException e) {
						}
					}
					if(nonwh) {
						if(!Character.isLetter(ch)) {
							if(word != -1) {
								all.add(s.substring(word, i));
								strings.add(s.substring(word, i));
							}
							all.add(s.substring(i, i + 1));
							strings.add(s.substring(i, i +1));
							word = -1;
						} else if(word == -1) {
							word = i;
						} 
					} else {
						if(word != -1) {
							all.add(s.substring(word, i));
							strings.add(s.substring(word, i));
						}
						word = -1;
					}
				}
			}
		}
		
		private void parseLocation2(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
			String lw = phrase.getLastWord();
			SearchWord sw = phrase.getLastSelectedWord();
			LatLon l = null;
			if (sw != null && sw.getType() == ObjectType.UNKNOWN_NAME_FILTER) {
				l = parseLocation(sw.getWord() + ", " + lw);
			}
			if (l == null) {
				l = parseLocation(lw);
			}
			if (l != null) {
				SearchResult sp = new SearchResult(phrase);
				sp.priority = SEARCH_LOCATION_PRIORITY;
				sp.object = sp.location = l;
				sp.localeName = ((float) sp.location.getLatitude()) + ", " + ((float) sp.location.getLongitude());
				sp.objectType = ObjectType.LOCATION;
				sp.wordsSpan = 2;
				resultMatcher.publish(sp);
			} else if (phrase.isNoSelectedType()) {
				// SearchResult sp = new SearchResult(phrase);
				// sp.priority = SEARCH_LOCATION_PRIORITY;
				// sp.object = sp.location = new LatLon(dd, 0);
				// sp.localeName = ((float) sp.location.getLatitude()) + ", <input> ";
				// sp.objectType = ObjectType.PARTIAL_LOCATION;
				// resultMatcher.publish(sp);
			}
		}
		
		private void parseLocation(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
			String lw = phrase.getLastWord();
			SearchWord sw = phrase.getLastSelectedWord();
			double dd = LocationConvert.convert(lw, false);
			if(!Double.isNaN(dd)) {
				double pd = Double.NaN;
				if(sw != null && sw.getType() == ObjectType.UNKNOWN_NAME_FILTER) {
					if(isKindOfNumber(sw.getWord())) {
						pd = LocationConvert.convert(sw.getWord(), false);
					}
				}
				if(!Double.isNaN(pd)) {
					SearchResult sp = new SearchResult(phrase);
					sp.priority = SEARCH_LOCATION_PRIORITY;
					sp.object = sp.location = new LatLon(pd, dd);
					sp.localeName = ((float)sp.location.getLatitude()) +", " + ((float) sp.location.getLongitude());
					sp.objectType = ObjectType.LOCATION;
					sp.wordsSpan = 2;
					resultMatcher.publish(sp);
				} else if (phrase.isNoSelectedType()) {
					SearchResult sp = new SearchResult(phrase);
					sp.priority = SEARCH_LOCATION_PRIORITY;
					sp.object = sp.location = new LatLon(dd, 0);
					sp.localeName = ((float) sp.location.getLatitude()) + ", <input> ";
					sp.objectType = ObjectType.PARTIAL_LOCATION;
					resultMatcher.publish(sp);
				}
			}
		}


		private boolean parseUrl(SearchPhrase phrase, SearchResultMatcher resultMatcher) {
			String text = phrase.getLastWord();
			GeoParsedPoint pnt = GeoPointParserUtil.parse(text);
			int wordsSpan= 1;
			List<SearchWord> lst = phrase.getWords();
			for (int i = lst.size() - 1; i >= 0 && (pnt == null || !pnt.isGeoPoint()); i--) {
				SearchWord w = lst.get(i);
				if (w.getType() != ObjectType.UNKNOWN_NAME_FILTER) {
					break;
				}
				text = w.getWord() + "," + text;
				wordsSpan++;
				pnt = GeoPointParserUtil.parse(text);
				if (pnt != null && pnt.isGeoPoint()) {
					break;
				}
			}
			if(pnt != null && pnt.isGeoPoint()) {
				SearchResult sp = new SearchResult(phrase);
				sp.priority = 0;
				sp.object = pnt;
				sp.wordsSpan = wordsSpan;
				sp.location = new LatLon(pnt.getLatitude(), pnt.getLongitude());
				sp.localeName = ((float)pnt.getLatitude()) +", " + ((float) pnt.getLongitude());
				if(pnt.getZoom() > 0) {
					sp.preferredZoom = pnt.getZoom();
				}
				sp.objectType = ObjectType.LOCATION;
				resultMatcher.publish(sp);
				return true;
			}
			return false;
		}
		
		@Override
		public int getSearchPriority(SearchPhrase p) {
			return SEARCH_LOCATION_PRIORITY;
		}
	}
	
}
