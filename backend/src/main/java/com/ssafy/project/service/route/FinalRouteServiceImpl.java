package com.ssafy.project.service.route;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringTokenizer;
import javax.net.ssl.HttpsURLConnection;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import com.ssafy.project.dao.group.GroupAlarmDao;
import com.ssafy.project.dao.route.RouteDao;
import com.ssafy.project.dao.route.SubscribeDao;
import com.ssafy.project.dao.user.CustomDao;
import com.ssafy.project.dao.user.LocationDao;
import com.ssafy.project.model.api.ApiProperties;
import com.ssafy.project.model.group.GroupAlarm;
import com.ssafy.project.model.group.GroupAlarmId;
import com.ssafy.project.model.group.GroupAlarmRegisterRequest;
import com.ssafy.project.model.route.Route;
import com.ssafy.project.model.route.RouteFindRequest;
import com.ssafy.project.model.route.RouteFindWithoutRequest;
import com.ssafy.project.model.route.Subscribe;
import com.ssafy.project.model.user.Custom;
import com.ssafy.project.model.user.Location;
import com.ssafy.project.model.user.LocationId;

@Service
public class FinalRouteServiceImpl implements FinalRouteService {

	@Autowired
	private ApiProperties apiProperties;

	@Autowired
	private RouteDao routeDao;

	@Autowired
	private LocationDao locationDao;

	@Autowired
	private GroupAlarmDao groupAlarmDao;

	@Autowired
	private CustomDao customDao;

	@Autowired
	private SubscribeDao subscribeDao;

	public static final Logger logger = LoggerFactory.getLogger(FinalRouteServiceImpl.class);

	@Override
	public ResponseEntity<Map<String, Object>> findLastWithoutUser(RouteFindWithoutRequest routeFindWithoutRequest) {

		HttpStatus status = null;
		Map<String, Object> resultMap = new HashMap<String, Object>();

		final String openUrl = "https://api.odsay.com/v1/api/searchPubTransPathT?lang=0&SX=" + routeFindWithoutRequest.getStartX() + "&SY=" + routeFindWithoutRequest.getStartY() + "&EX="
				+ routeFindWithoutRequest.getEndX() + "&EY=" + routeFindWithoutRequest.getEndY() + "&apiKey=" + apiProperties.getKey();

		try {
			URL url = new URL(openUrl);

			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray path = (JSONArray) response.get("path");

			int lastOneIdx = 0; // 가장 늦게 탈 수 있는 경로의 번호
			int lastOneTime = Integer.MIN_VALUE; // 가장 늦게 탈 수 있는 경로의 출발 시간 (1245 => 12시 45분 출발)
			long remainSecond = 0; // 도착시간까지 남은 시간
			int[] dayOfMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

			/* 사용자가 원하는 도착시간 정보 */
			String[] arriveTime = routeFindWithoutRequest.getArriveTime().split(" ");
			int arriveMonth = Integer.parseInt(arriveTime[0].substring(5, 7)); // 월
			int arriveDay = Integer.parseInt(arriveTime[0].substring(8, 10)); // 일

			Calendar today = Calendar.getInstance(); // 오늘 날짜 => 요일을 확인하기 위해서
			today.setTime(new Date());

			int dayOfWeek = today.get(Calendar.DAY_OF_WEEK); // 1: 일요일 ~ 7: 토요일
			int nowMonth = today.get(Calendar.MONTH) + 1;
			int nowDay = today.get(Calendar.DATE);

			if (arriveMonth != nowMonth) { // 월이 다른 경우
				dayOfWeek += ((dayOfMonth[nowMonth] - nowDay) + arriveDay);

				if (dayOfWeek > 7)
					dayOfWeek -= 7;
			} else {
				if (arriveDay != nowDay) { // 일이 다른 경우, 도착 일자에 맞추기 위해서
					dayOfWeek += (arriveDay - nowDay);

					if (dayOfWeek > 7)
						dayOfWeek -= 7;
				}
			}

			for (int i = 0; i < path.size(); i++) {
				JSONObject paths = (JSONObject) path.get(i);

				/* 각 전체 경로 확인 */
				JSONArray subPath = (JSONArray) paths.get("subPath");
				int subPathStartTime = 2800; // 해당 경로의 출발 시간 => 막차 시간이기 때문에 시작 시간을 새벽 4시로 우선 설정

				for (int j = subPath.size() - 1; j >= 0; j--) {
					JSONObject smallSubPath = (JSONObject) subPath.get(j);
					int trafficType = Integer.parseInt(String.valueOf(smallSubPath.get("trafficType")));

					/* 모든 TrafficType에 대해서 구간 시간을 뺀 뒤 그 시간에 탈 수 있는 막차를 구해야함! */
					subPathStartTime -= Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));
					if (subPathStartTime % 100 >= 60)
						subPathStartTime -= 40;

					if (trafficType == 1) { // 지하철
						int subwayStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발역의 ID
						int subwayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode"))); // 상, 하행 정보

						/* 지하철 막차의 출발 시간 반환 */
						subPathStartTime = findSubwayLastStartTime(subwayStartId, subwayCode, subPathStartTime, dayOfWeek);
					} else if (trafficType == 2) { // 버스
						JSONArray laneInfos = (JSONArray) smallSubPath.get("lane");
						JSONObject laneInfo = (JSONObject) laneInfos.get(0);
						int busId = Integer.parseInt(String.valueOf(laneInfo.get("busID")));
						int busStationStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발 정류장 ID

						/* 버스 막차의 출발 시간 반환 */
						subPathStartTime = findBusLastStartTime(busStationStartId, busId, subPathStartTime, dayOfWeek);
					}
				}

				/* 가장 늦게 탈 수 있는 경로 확인 */
				if (lastOneTime < subPathStartTime) {
					lastOneIdx = i;
					lastOneTime = subPathStartTime;
				}
			}

			/* 막차 도착 시간 만들기 */
			StringBuilder sb = new StringBuilder();
			sb.append(arriveTime[0].substring(0, 4) + "-"); // 년

			if ((dayOfMonth[arriveMonth] == arriveDay) && lastOneTime >= 2400) { // 월이 넘어가는 경우
				arriveMonth++;
				arriveDay = 1;
			} else if (lastOneTime >= 2400) {
				arriveDay++;
				lastOneTime -= 2400;
			}

			sb.append(arriveMonth + "-"); // 월
			sb.append(arriveDay + " "); // 일
			sb.append((lastOneTime / 100) + ":" + (lastOneTime % 100));

			/* 현재 시간으로부터 막차 도착시간까지 남은 시간 초로 전달 */
			Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
			Calendar lastDate = Calendar.getInstance();
			lastDate.setTime(date);

			remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

			resultMap.put("inputtime", routeFindWithoutRequest.getArriveTime());
			resultMap.put("arrivetime", sb.toString());
			resultMap.put("routeinfo", (JSONObject) path.get(lastOneIdx));
			resultMap.put("totaltime", remainSecond);

			urlConnection.disconnect();
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("막차 시간 계산 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<Map<String, Object>>(resultMap, status);
	}

	@Override
	public ResponseEntity<Route> findLastWithUser(RouteFindRequest routeFindRequest) {

		HttpStatus status = null;
		Route resultRoute = new Route();

		final String openUrl = "https://api.odsay.com/v1/api/searchPubTransPathT?lang=0&SX=" + routeFindRequest.getStartX() + "&SY=" + routeFindRequest.getStartY() + "&EX="
				+ routeFindRequest.getEndX() + "&EY=" + routeFindRequest.getEndY() + "&apiKey=" + apiProperties.getKey();

		Custom custom = customDao.findCustomByUid(routeFindRequest.getUid());

		try {
			Route route = new Route();

			route.setUid(routeFindRequest.getUid());
			route.setStartaddress(routeFindRequest.getStartAddress());
			route.setStartlongitude(routeFindRequest.getStartX());
			route.setStartlatitude(routeFindRequest.getStartY());
			route.setEndaddress(routeFindRequest.getEndAddress());
			route.setEndlongitude(routeFindRequest.getEndX());
			route.setEndlatitude(routeFindRequest.getEndY());
			route.setTimetype(0);

			if (!routeFindRequest.getAlarmName().isEmpty()) {
				route.setAlarmname(routeFindRequest.getAlarmName());
			}

			URL url = new URL(openUrl);

			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray path = (JSONArray) response.get("path");

			int lastOneIdx = 0; // 가장 늦게 탈 수 있는 경로의 번호
			int lastOneTime = Integer.MIN_VALUE; // 가장 늦게 탈 수 있는 경로의 출발 시간 (1245 => 12시 45분 출발)
			long remainSecond = 0; // 도착시간까지 남은 시간
			int[] dayOfMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

			/* 사용자가 원하는 도착시간 정보 */
			String[] arriveTime = routeFindRequest.getArriveTime().split(" ");
			int arriveMonth = Integer.parseInt(arriveTime[0].substring(5, 7)); // 월
			int arriveDay = Integer.parseInt(arriveTime[0].substring(8, 10)); // 일

			Calendar today = Calendar.getInstance(); // 오늘 날짜 => 요일을 확인하기 위해서
			today.setTime(new Date());

			int dayOfWeek = today.get(Calendar.DAY_OF_WEEK); // 1: 일요일 ~ 7: 토요일
			int nowMonth = today.get(Calendar.MONTH) + 1;
			int nowDay = today.get(Calendar.DATE);

			if (arriveMonth != nowMonth) { // 월이 다른 경우
				dayOfWeek += ((dayOfMonth[nowMonth] - nowDay) + arriveDay);

				if (dayOfWeek > 7)
					dayOfWeek -= 7;
			} else {
				if (arriveDay != nowDay) { // 일이 다른 경우, 도착 일자에 맞추기 위해서
					dayOfWeek += (arriveDay - nowDay);

					if (dayOfWeek > 7)
						dayOfWeek -= 7;
				}
			}

			for (int i = 0; i < path.size(); i++) {
				JSONObject paths = (JSONObject) path.get(i);

				/* 각 전체 경로 확인 */
				JSONArray subPath = (JSONArray) paths.get("subPath");
				int subPathStartTime = 2800; // 해당 경로의 출발 시간 => 막차 시간이기 때문에 시작 시간을 새벽 4시로 우선 설정

				for (int j = subPath.size() - 1; j >= 0; j--) {
					JSONObject smallSubPath = (JSONObject) subPath.get(j);
					int trafficType = Integer.parseInt(String.valueOf(smallSubPath.get("trafficType")));

					/* 모든 TrafficType에 대해서 구간 시간을 뺀 뒤 그 시간에 탈 수 있는 막차를 구해야함! */
					subPathStartTime -= Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));

					if (trafficType == 3) {
						if (Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) >= 2) {
							switch (custom.getSpeed()) {
								case 2: // 느림
									subPathStartTime -= Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2;
									smallSubPath.put("sectionTime", Integer
											.toString(Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) + Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2));
									break;
								case 6: // 빠름
									subPathStartTime += Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2;
									smallSubPath.put("sectionTime", Integer
											.toString(Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) - Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2));
									break;
							}
						}
					}

					if (subPathStartTime % 100 >= 60)
						subPathStartTime -= 40;

					if (trafficType == 1) { // 지하철
						int subwayStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발역의 ID
						int subwayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode"))); // 상, 하행 정보

						/* 지하철 막차의 출발 시간 반환 */
						subPathStartTime = findSubwayLastStartTime(subwayStartId, subwayCode, subPathStartTime, dayOfWeek);
					} else if (trafficType == 2) { // 버스
						JSONArray laneInfos = (JSONArray) smallSubPath.get("lane");
						JSONObject laneInfo = (JSONObject) laneInfos.get(0);
						int busId = Integer.parseInt(String.valueOf(laneInfo.get("busID")));
						int busStationStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발 정류장 ID

						/* 버스 막차의 출발 시간 반환 */
						subPathStartTime = findBusLastStartTime(busStationStartId, busId, subPathStartTime, dayOfWeek);
					}
				}

				/* 가장 늦게 탈 수 있는 경로 확인 */
				if (lastOneTime < subPathStartTime) {
					lastOneIdx = i;
					lastOneTime = subPathStartTime;
				}
			}

			/* 막차 도착 시간 만들기 */
			StringBuilder sb = new StringBuilder();
			sb.append(arriveTime[0].substring(0, 4) + "-"); // 년

			if ((dayOfMonth[arriveMonth] == arriveDay) && lastOneTime >= 2400) { // 월이 넘어가는 경우
				arriveMonth++;
				arriveDay = 1;
			} else if (lastOneTime >= 2400) {
				arriveDay++;
				lastOneTime -= 2400;
			}

			lastOneTime -= custom.getSparetime(); // 사용자 여분 시간 추가

			if (lastOneTime % 100 >= 60)
				lastOneTime -= 40;


			if (arriveMonth < 10) {
				sb.append(0);
			}
			sb.append(arriveMonth + "-"); // 월

			if (arriveDay < 10) {
				sb.append(0);
			}
			sb.append(arriveDay + " "); // 일

			sb.append((lastOneTime / 100) + ":" + (lastOneTime % 100));

			/* 현재 시간으로부터 막차 도착시간까지 남은 시간 초로 전달 */
			Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
			Calendar lastDate = Calendar.getInstance();
			lastDate.setTime(date);

			remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

			route.setInputtime(routeFindRequest.getArriveTime());
			route.setArrivetime(sb.toString());
			route.setRouteinfo(((JSONObject) path.get(lastOneIdx)).toString());
			route.setTotaltime((int) remainSecond);

			resultRoute = routeDao.save(route);

			urlConnection.disconnect();
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("막차 시간 계산 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<Route>(resultRoute, status);
	}

	public int findSubwayLastStartTime(int subwayStartId, int subwayCode, int startTime, int dayOfWeek) {

		StringTokenizer st = null;
		int newTime = 0;

		final String subwayStationInfoOpenUrl = "https://api.odsay.com/v1/api/subwayTimeTable?lang=0&stationID=" + subwayStartId + "&wayCode=" + subwayCode + "&apiKey=" + apiProperties.getKey();

		try {
			URL subwayUrl = new URL(subwayStationInfoOpenUrl);

			HttpsURLConnection subwayUrlConnection = (HttpsURLConnection) subwayUrl.openConnection();
			subwayUrlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(subwayUrlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");

			/* 지하철 전체 경로 확인 */
			/* 요일 구분 */
			JSONObject subwayList = null;
			if (dayOfWeek == 1) { // 일요일
				subwayList = (JSONObject) response.get("SatList");
			} else if (dayOfWeek == 7) { // 토요일
				subwayList = (JSONObject) response.get("SunList");
			} else { // 평일
				subwayList = (JSONObject) response.get("OrdList");
			}

			/* 상, 하행 구분 */
			JSONObject wayList = null;
			if (subwayCode == 1) { // 상행
				wayList = (JSONObject) subwayList.get("up");
			} else if (subwayCode == 2) { // 하행
				wayList = (JSONObject) subwayList.get("down");
			}

			/* 가장 마지막 시간의 지하철 시간 받아오기 */
			JSONArray timeList = (JSONArray) wayList.get("time");
			o: for (int i = timeList.size() - 1; i >= 0; i--) {
				JSONObject lastTimeList = (JSONObject) timeList.get(i);

				int hour = Integer.parseInt(String.valueOf(lastTimeList.get("Idx"))); // 시간

				if (hour <= (startTime / 100)) { // 우선 동일한 시간인 경우를 확인
					if ((startTime / 100) == hour && (startTime % 100) == 0) // 시간이 같은데 0분이 경우는 무조건 전 시간 가장 늦은 애로!
						continue;

					// 1. 시 정하기
					newTime += hour * 100;

					// 2. 분 정하기
					String minute = (String) lastTimeList.get("list");
					st = new StringTokenizer(minute, " ");

					ArrayList<Integer> minuteList = new ArrayList<Integer>();
					while (st.hasMoreTokens()) {
						minuteList.add(Integer.parseInt(st.nextToken().substring(0, 2)));
					}
					Collections.sort(minuteList, Collections.reverseOrder()); // 내림차순 정렬

					if ((startTime % 100) == 0) {
						newTime += minuteList.get(0);
						break o;
					} else {
						for (int j = 0; j < minuteList.size(); j++) {
							if ((startTime % 100) >= minuteList.get(j)) {
								newTime += minuteList.get(j);
								break o;
							}
						}
					}
				}
			}

			subwayUrlConnection.disconnect();
		} catch (Exception e) {
			logger.error("지하철 막차 시간 계산 실패 : {}", e);
		}

		return newTime;
	}

	public int findBusLastStartTime(int busStationStartId, int busId, int startTime, int dayOfWeek) {

		StringTokenizer st = null;
		int newTime = 0;

		final String busRouteInfoOpenUrl = "https://api.odsay.com/v1/api/busLaneDetail?lang=0&busID=" + busId + "&apiKey=" + apiProperties.getKey();

		try {
			URL busUrl = new URL(busRouteInfoOpenUrl);

			HttpsURLConnection busUrlConnection = (HttpsURLConnection) busUrl.openConnection();
			busUrlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(busUrlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");

			/* 해당 노선의 막차 시간 가져오기 */
			String busLastTime = (String) response.get("busLastTime");

			st = new StringTokenizer(busLastTime, ":");
			newTime += Integer.parseInt(st.nextToken()) * 100; // 시
			newTime += Integer.parseInt(st.nextToken()); // 분

			/* 요일에 따른 버스 간격 결정 */
			int busIntevalWeek = 0;
			if (dayOfWeek == 1) { // 일요일
				busIntevalWeek = Integer.parseInt(String.valueOf(response.get("bus_Interval_Sun")));
			} else if (dayOfWeek == 7) { // 토요일
				busIntevalWeek = Integer.parseInt(String.valueOf(response.get("bus_Interval_Sat")));
			} else { // 평일
				busIntevalWeek = Integer.parseInt(String.valueOf(response.get("bus_Interval_Week")));
			}

			/* 버스 출발 정거장의 위치를 확인 후 막차 시간 계산 */
			JSONArray stationList = (JSONArray) response.get("station");
			for (int i = 0; i < stationList.size(); i++) {
				JSONObject station = (JSONObject) stationList.get(i);

				if (Integer.parseInt(String.valueOf(station.get("stationID"))) == busStationStartId) {
					int stationDistance = Integer.parseInt(String.valueOf(station.get("stationDistance")));

					/* 서울 버스의 평균 속도를 20km/h라는 가정을 하고 막차 시간 계산 */
					newTime += (int) Math.ceil((stationDistance * 60) / 20000);
				}
			}

			// 분이 60을 넘어가는 경우
			if (newTime % 100 >= 60)
				newTime += 40;

			/* 확인된 막차 시간에서 interval을 빼면서 startTime보다 작은 가장 늦은 시간 전달 */
			while (newTime > startTime) {
				newTime -= busIntevalWeek;

				if (newTime % 100 >= 60)
					newTime -= 40;
			}

			busUrlConnection.disconnect();
		} catch (Exception e) {
			logger.error("버스 막차 시간 계산 실패 : {}", e);
		}

		return newTime;
	}

	@Override
	public ResponseEntity<List<Object>> info(String uid) {

		HttpStatus status = null;
		List<Object> resultList = new ArrayList<Object>();

		try {
			List<Route> routeList = routeDao.findAllByUidWithoutGroupInfo(uid);
			List<Subscribe> subscribeList = subscribeDao.findAllByUid(uid);

			if (!routeList.isEmpty() && subscribeList.isEmpty()) {
				Collections.sort(routeList, new Comparator<Route>() {

					@Override
					public int compare(Route o1, Route o2) {
						return o1.getRegisterdate().compareTo(o1.getRegisterdate());
					}

				});

				resultList.addAll(routeList);
			} else if (routeList.isEmpty() && !subscribeList.isEmpty()) {
				Collections.sort(subscribeList, new Comparator<Subscribe>() {

					@Override
					public int compare(Subscribe o1, Subscribe o2) {
						return o1.getRegisterdate().compareTo(o1.getRegisterdate());
					}

				});

				resultList.addAll(subscribeList);
			} else if (!routeList.isEmpty() && !subscribeList.isEmpty()) {
				while (!routeList.isEmpty() && !subscribeList.isEmpty()) {
					if (routeList.get(0).getRegisterdate().compareTo(subscribeList.get(0).getRegisterdate()) > 0) {
						resultList.add(subscribeList.get(0));
						subscribeList.remove(0);
					} else if (routeList.get(0).getRegisterdate().compareTo(subscribeList.get(0).getRegisterdate()) < 0) {
						resultList.add(routeList.get(0));
						routeList.remove(0);
					}
				}

				if (!routeList.isEmpty()) {
					while (!routeList.isEmpty()) {
						resultList.add(routeList.get(0));
						routeList.remove(0);
					}
				} else if (!subscribeList.isEmpty()) {
					while (!subscribeList.isEmpty()) {
						resultList.add(subscribeList.get(0));
						subscribeList.remove(0);
					}
				}
			}

			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("사용자 알람 정보 가져오기 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<List<Object>>(resultList, status);
	}

	@Override
	public ResponseEntity<List<Route>> infoGroup(String uid) {
		HttpStatus status = null;
		List<Route> resultList = null;

		try {
			resultList = routeDao.findAllByUidWithGroupInfo(uid);
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("그룹 정보를 포함하는 경로 정보 가져오기 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<List<Route>>(resultList, status);
	}

	@Override
	public ResponseEntity<String> delete(int routeId) {

		HttpStatus status = null;
		String result = "삭제 안돼요!";

		try {
			Route route = routeDao.findRouteByRouteid(routeId);

			if (route != null) {
				routeDao.delete(route);
				result = "삭제 완료!";
			}

			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("삭제 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<String>(result, status);

	}

	@Override
	public ResponseEntity<String> registerLast(GroupAlarmRegisterRequest groupAlarmRegisterRequest) {

		HttpStatus status = null;
		String result = "등록 미완료";

		List<String> userList = groupAlarmRegisterRequest.getUids();

		try {
			GroupAlarm groupAlarm = groupAlarmDao.findGroupAlarmByGroupalarmidUid(userList.get(0));

			for (String user : userList) {
				LocationId locationId = new LocationId(1, user); // 무조건 집에서 출발한다고 가정
				Location userLocation = locationDao.findLocationByLocationid(locationId);

				String openUrl = "https://api.odsay.com/v1/api/searchPubTransPathT?lang=0&SX=" + userLocation.getLongitude() + "&SY=" + userLocation.getLatitude() + "&EX="
						+ groupAlarmRegisterRequest.getEndX() + "&EY=" + groupAlarmRegisterRequest.getEndY() + "&apiKey=" + apiProperties.getKey();

				Route route = new Route();

				route.setUid(user);
				route.setStartaddress(userLocation.getAddress());
				route.setStartlongitude(userLocation.getLongitude());
				route.setStartlatitude(userLocation.getLatitude());
				route.setEndaddress(groupAlarmRegisterRequest.getEndAddress());
				route.setEndlongitude(groupAlarmRegisterRequest.getEndX());
				route.setEndlatitude(groupAlarmRegisterRequest.getEndY());
				route.setGroupinfo(groupAlarm.getGroupalarmid().getGroupid());

				if (!groupAlarmRegisterRequest.getAlarmName().isEmpty()) {
					route.setAlarmname(groupAlarmRegisterRequest.getAlarmName());
				}

				URL url = new URL(openUrl);

				HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
				urlConnection.setRequestMethod("GET");

				BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

				JSONParser parser = new JSONParser();
				JSONObject obj = (JSONObject) parser.parse(br);
				JSONObject response = (JSONObject) obj.get("result");
				JSONArray path = (JSONArray) response.get("path");

				int lastOneIdx = 0; // 가장 늦게 탈 수 있는 경로의 번호
				int lastOneTime = Integer.MIN_VALUE; // 가장 늦게 탈 수 있는 경로의 출발 시간 (1245 => 12시 45분 출발)
				long remainSecond = 0; // 도착시간까지 남은 시간
				int[] dayOfMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

				/* 사용자가 원하는 도착시간 정보 */
				String[] arriveTime = groupAlarmRegisterRequest.getArriveTime().split(" ");
				int arriveMonth = Integer.parseInt(arriveTime[0].substring(5, 7)); // 월
				int arriveDay = Integer.parseInt(arriveTime[0].substring(8, 10)); // 일

				Calendar today = Calendar.getInstance(); // 오늘 날짜 => 요일을 확인하기 위해서
				today.setTime(new Date());

				int dayOfWeek = today.get(Calendar.DAY_OF_WEEK); // 1: 일요일 ~ 7: 토요일
				int nowMonth = today.get(Calendar.MONTH) + 1;
				int nowDay = today.get(Calendar.DATE);

				if (arriveMonth != nowMonth) { // 월이 다른 경우
					dayOfWeek += ((dayOfMonth[nowMonth] - nowDay) + arriveDay);

					if (dayOfWeek > 7)
						dayOfWeek -= 7;
				} else {
					if (arriveDay != nowDay) { // 일이 다른 경우, 도착 일자에 맞추기 위해서
						dayOfWeek += (arriveDay - nowDay);

						if (dayOfWeek > 7)
							dayOfWeek -= 7;
					}
				}

				for (int i = 0; i < path.size(); i++) {
					JSONObject paths = (JSONObject) path.get(i);

					/* 각 전체 경로 확인 */
					JSONArray subPath = (JSONArray) paths.get("subPath");
					int subPathStartTime = 2800; // 해당 경로의 출발 시간 => 막차 시간이기 때문에 시작 시간을 새벽 4시로 우선 설정

					for (int j = subPath.size() - 1; j >= 0; j--) {
						JSONObject smallSubPath = (JSONObject) subPath.get(j);
						int trafficType = Integer.parseInt(String.valueOf(smallSubPath.get("trafficType")));

						/* 모든 TrafficType에 대해서 구간 시간을 뺀 뒤 그 시간에 탈 수 있는 막차를 구해야함! */
						subPathStartTime -= Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));
						if (subPathStartTime % 100 >= 60)
							subPathStartTime -= 40;

						if (trafficType == 1) { // 지하철
							int subwayStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발역의 ID
							int subwayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode"))); // 상, 하행 정보

							/* 지하철 막차의 출발 시간 반환 */
							subPathStartTime = findSubwayLastStartTime(subwayStartId, subwayCode, subPathStartTime, dayOfWeek);
						} else if (trafficType == 2) { // 버스
							JSONArray laneInfos = (JSONArray) smallSubPath.get("lane");
							JSONObject laneInfo = (JSONObject) laneInfos.get(0);
							int busId = Integer.parseInt(String.valueOf(laneInfo.get("busID")));
							int busStationStartId = Integer.parseInt(String.valueOf(smallSubPath.get("startID"))); // 출발 정류장 ID

							/* 버스 막차의 출발 시간 반환 */
							subPathStartTime = findBusLastStartTime(busStationStartId, busId, subPathStartTime, dayOfWeek);
						}
					}

					/* 가장 늦게 탈 수 있는 경로 확인 */
					if (lastOneTime < subPathStartTime) {
						lastOneIdx = i;
						lastOneTime = subPathStartTime;
					}
				}

				/* 막차 도착 시간 만들기 */
				StringBuilder sb = new StringBuilder();
				sb.append(arriveTime[0].substring(0, 4) + "-"); // 년

				if ((dayOfMonth[arriveMonth] == arriveDay) && lastOneTime >= 2400) { // 월이 넘어가는 경우
					arriveMonth++;
					arriveDay = 1;
				} else if (lastOneTime >= 2400) {
					arriveDay++;
					lastOneTime -= 2400;
				}

				sb.append(arriveMonth + "-"); // 월
				sb.append(arriveDay + " "); // 일
				sb.append((lastOneTime / 100) + ":" + (lastOneTime % 100));

				/* 현재 시간으로부터 막차 도착시간까지 남은 시간 초로 전달 */
				Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
				Calendar lastDate = Calendar.getInstance();
				lastDate.setTime(date);

				remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

				route.setArrivetime(sb.toString());
				route.setRouteinfo(((JSONObject) path.get(lastOneIdx)).toString());
				route.setTotaltime((int) remainSecond);

				routeDao.save(route);

				GroupAlarmId groupAlarmId = new GroupAlarmId(groupAlarm.getGroupalarmid().getGroupid(), user);
				Optional<GroupAlarm> groupAlarmOpt = groupAlarmDao.findOptionalByGroupalarmid(groupAlarmId);

				groupAlarmOpt.ifPresent(selectGroupAlarm -> {
					selectGroupAlarm.setGalarmcnt(selectGroupAlarm.getGalarmcnt() + 1);
					groupAlarmDao.save(selectGroupAlarm);
				});
			}
			result = "등록 완료";
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("막차를 위한 그룹 알람 등록 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}

		return new ResponseEntity<String>(result, status);
	}
}
