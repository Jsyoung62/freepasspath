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
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
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
import com.ssafy.project.dao.user.CustomDao;
import com.ssafy.project.dao.user.LocationDao;
import com.ssafy.project.model.api.ApiProperties;
import com.ssafy.project.model.group.GroupAlarm;
import com.ssafy.project.model.group.GroupAlarmId;
import com.ssafy.project.model.group.GroupAlarmRegisterRequest;
import com.ssafy.project.model.route.Route;
import com.ssafy.project.model.route.RouteFindRequest;
import com.ssafy.project.model.route.RouteFindWithoutRequest;
import com.ssafy.project.model.user.Custom;
import com.ssafy.project.model.user.Location;
import com.ssafy.project.model.user.LocationId;

@Service
public class RouteServiceImpl implements RouteService {

	@Autowired
	private ApiProperties apiProperties;

	@Autowired
	private CustomDao customDao;

	@Autowired
	private RouteDao routeDao;

	@Autowired
	private GroupAlarmDao groupAlarmDao;

	@Autowired
	private LocationDao locationDao;

	public static final Logger logger = LoggerFactory.getLogger(RouteServiceImpl.class);

	static int startBusStationIdx = 0;

	@Override
	public ResponseEntity<Map<String, Object>> findRouteWithoutUser(RouteFindWithoutRequest routeFindWithoutRequest) {

		HttpStatus status = null;
		Map<String, Object> resultMap = new HashMap<String, Object>();

		final String openUrl = "https://api.odsay.com/v1/api/searchPubTransPathT?lang=0&SX=" + routeFindWithoutRequest.getStartX() + "&SY=" + routeFindWithoutRequest.getStartY() + "&EX="
				+ routeFindWithoutRequest.getEndX() + "&EY=" + routeFindWithoutRequest.getEndY() + "&apiKey=" + apiProperties.getKey();

		try {

			String startTime = new String();
			JSONObject resultObject = new JSONObject();

			// 현재시간
			Calendar today = Calendar.getInstance();
			int nowHour = today.get(Calendar.HOUR_OF_DAY);
			int nowMin = today.get(Calendar.MINUTE);

			URL url = new URL(openUrl);

			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray path = (JSONArray) response.get("path");

			// 세부 경로들 계산
			loop: for (int i = 0; i < path.size(); i++) {
				JSONObject infos = (JSONObject) path.get(i);

				// step1.경로 전체 소요시간 구하기
				JSONObject info = (JSONObject) infos.get("info");
				int totalTime = Integer.parseInt(String.valueOf(info.get("totalTime")));

				String[] arriveTime = routeFindWithoutRequest.getArriveTime().split(" ");

				// step2.실시간 반영 전 출발시간(도착시간-소요시간)구하기
				startTime = CalculateTime(arriveTime[1], totalTime, 2);
				resultObject = infos;

				String[] start = startTime.split(":");
				int startHour = Integer.parseInt(start[0]);
				int startMinute = Integer.parseInt(start[1]);

				// step3.(실시간 반영전 출발시간 - 현재시간)이 30분 이하일 경우에만 실시간 출발시간 체크
				if ((startHour * 60 + startMinute) - (nowHour * 60 + nowMin) <= 30) {

					// 첫 대중교통의 실시간 정보 반영한 출발시간 구하기
					JSONArray subPath = (JSONArray) infos.get("subPath");
					int walkTime = 0;

					for (int j = 0; j < subPath.size(); j++) {
						JSONObject smallSubPath = (JSONObject) subPath.get(j);

						// trafficType 1:지하철, 2:버스, 3:도보
						long trafficType = (Long) smallSubPath.get("trafficType");

						// 지하철 운행시간 검색
						if (trafficType == 1) {
							int stationID = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
							int wayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode")));

							String tmpTime = CalculateTime(startTime, walkTime, 1);
							String realStartTime = CalculateTime(TimeTableSubway(stationID, wayCode, tmpTime), walkTime, 2);
							startTime = realStartTime;

							break loop;
						}

						// 버스 실시간 검색
						else if (trafficType == 2) {
							int startBusStationId = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
							JSONArray lane = (JSONArray) smallSubPath.get("lane");
							JSONObject smallLane = (JSONObject) lane.get(0);

							int busID = Integer.parseInt(String.valueOf(smallLane.get("busID")));

							String tmpTime = CalculateTime(startTime, walkTime, 1);
							String realStartTime = CalculateTime(RealTimeBus(busID, startBusStationId, tmpTime), walkTime, 2);
							startTime = realStartTime;

							break loop;
						}

						// 도보일경우 다음 대중교통 체크
						else if (trafficType == 3) {
							int sectionTime = Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));
							walkTime += sectionTime;
							continue;
						}

					}
				}
			}

			/* 결과 출발 시간 만들기 */
			StringBuilder sb = new StringBuilder();
			String[] arriveTime = routeFindWithoutRequest.getArriveTime().split(" ");

			sb.append(arriveTime[0] + " "); // 년-월-날
			sb.append(startTime);// 시간

			Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
			Calendar lastDate = Calendar.getInstance();
			lastDate.setTime(date);

			long remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

			resultMap.put("inputtime", routeFindWithoutRequest.getArriveTime());
			resultMap.put("arrivetime", sb.toString());
			resultMap.put("routeinfo", resultObject);
			resultMap.put("totaltime", remainSecond);

			urlConnection.disconnect();
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("경로 실시간 출발 시간 계산 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		return new ResponseEntity<Map<String, Object>>(resultMap, status);
	}

	@Override
	public ResponseEntity<Route> findRouteWithUser(RouteFindRequest routeFindRequest) {
		HttpStatus status = null;
		Route resultRoute = null;

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
			route.setTimetype(1);

			if (!routeFindRequest.getAlarmName().isEmpty()) {
				route.setAlarmname(routeFindRequest.getAlarmName());
			}

			String startTime = "";
			JSONObject resultObject = new JSONObject();

			// 현재시간
			Calendar today = Calendar.getInstance();
			int nowHour = today.get(Calendar.HOUR_OF_DAY);
			int nowMin = today.get(Calendar.MINUTE);

			URL url = new URL(openUrl);

			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray path = (JSONArray) response.get("path");

			// 회원가입되있는 사용자라면 커스텀 반영
			path = CheckCustom(path, routeFindRequest.getUid());

			// 세부 경로들 계산
			loop: for (int i = 0; i < path.size(); i++) {
				JSONObject infos = (JSONObject) path.get(i);

				// step1.경로 전체 소요시간 구하기
				JSONObject info = (JSONObject) infos.get("info");
				int totalTime = Integer.parseInt(String.valueOf(info.get("totalTime")));

				String[] arriveTime = routeFindRequest.getArriveTime().split(" ");

				// step2.실시간 반영 전 출발시간(도착시간-소요시간)구하기
				startTime = CalculateTime(arriveTime[1], totalTime, 2);

				resultObject = infos;

				String[] start = startTime.split(":");
				int startHour = Integer.parseInt(start[0]);
				int startMinute = Integer.parseInt(start[1]);

				// step3.(실시간 반영전 출발시간 - 현재시간)이 30분 이하일 경우에만 실시간 출발시간 체크
				if ((startHour * 60 + startMinute) - (nowHour * 60 + nowMin) <= 30) {

					// 첫 대중교통의 실시간 정보 반영한 출발시간 구하기
					JSONArray subPath = (JSONArray) infos.get("subPath");
					int walkTime = 0;

					for (int j = 0; j < subPath.size(); j++) {
						JSONObject smallSubPath = (JSONObject) subPath.get(j);

						// trafficType 1:지하철, 2:버스, 3:도보
						long trafficType = (Long) smallSubPath.get("trafficType");

						// 지하철 운행시간 검색
						if (trafficType == 1) {
							int stationID = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
							int wayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode")));
							String tmpTime = CalculateTime(startTime, walkTime, 1);
							String realStartTime = CalculateTime(TimeTableSubway(stationID, wayCode, tmpTime), walkTime, 2);
							startTime = realStartTime;
							break loop;
						}

						// 버스 실시간 검색
						else if (trafficType == 2) {
							int startBusStationId = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
							JSONArray lane = (JSONArray) smallSubPath.get("lane");
							JSONObject smallLane = (JSONObject) lane.get(0);
							int busID = Integer.parseInt(String.valueOf(smallLane.get("busID")));
							String tmpTime = CalculateTime(startTime, walkTime, 1);
							String realStartTime = CalculateTime(RealTimeBus(busID, startBusStationId, tmpTime), walkTime, 2);
							startTime = realStartTime;
							break loop;
						}

						// 도보일경우 다음 대중교통 체크
						else if (trafficType == 3) {
							int sectionTime = Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));

							if (sectionTime >= 2) {
								switch (custom.getSpeed()) {
									case 2: // 느림
										sectionTime += sectionTime / 2;
										smallSubPath.put("sectionTime", Integer
												.toString(Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) + Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2));
										break;
									case 6: // 빠름
										sectionTime -= sectionTime / 2;
										smallSubPath.put("sectionTime", Integer
												.toString(Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) - Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime"))) / 2));
										break;
								}
							}

							walkTime += sectionTime;
							continue;
						}

					}
				}
			}

			startTime = CalculateTime(startTime, custom.getSparetime(), 2);

			/* 결과 출발 시간 만들기 */
			StringBuilder sb = new StringBuilder();
			String[] arriveTime = routeFindRequest.getArriveTime().split(" ");

			sb.append(arriveTime[0] + " "); // 년-월-날
			sb.append(startTime);// 시간

			Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
			Calendar lastDate = Calendar.getInstance();
			lastDate.setTime(date);

			long remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

			route.setInputtime(routeFindRequest.getArriveTime());
			route.setArrivetime(sb.toString());
			route.setRouteinfo(resultObject.toString());
			route.setTotaltime((int) remainSecond);

			resultRoute = routeDao.save(route);

			urlConnection.disconnect();
			status = HttpStatus.OK;
		} catch (Exception e) {
			logger.error("경로 실시간 출발 시간 계산 실패 : {}", e);
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		return new ResponseEntity<Route>(resultRoute, status);
	}

	public JSONArray CheckCustom(JSONArray path, String uid) {
		JSONArray sortedJsonArray = new JSONArray();

		ArrayList<JSONObject> jsonValues = new ArrayList<JSONObject>();

		Custom custom = customDao.findCustomByUid(uid);

		int favorite = custom.getFavorites();// 지하철(1), 버스(2), 상관없음(0)
		int priority = custom.getPriority();// 최단시간(1), 최소 환승(2), 상관없음(0)

		// 교통수단 선호 반영
		if (favorite != 0) {
			for (int i = 0; i < path.size(); i++) {
				JSONObject infos = (JSONObject) path.get(i);

				// pathType : 1=지하철, 2=버스, 3=지하철+버스
				long pathType = (Long) infos.get("pathType");

				if (pathType == favorite) {
					jsonValues.add(infos);
				}
			}
		}

		// 경로 우선순위 반영
		if (priority != 0) {

			if (priority == 1) {

				Collections.sort(jsonValues, new Comparator<JSONObject>() {
					// You can change "Name" with "ID" if you want to sort by ID

					@Override
					public int compare(JSONObject a, JSONObject b) {
						int totalTimeA = 0;
						int totalTimeB = 0;

						try {
							JSONObject infoA = (JSONObject) a.get("info");
							JSONObject infoB = (JSONObject) b.get("info");

							totalTimeA = Integer.parseInt(String.valueOf(infoA.get("totalTime")));
							totalTimeB = Integer.parseInt(String.valueOf(infoB.get("totalTime")));
						} catch (JSONException e) {
							// do something
						}

						return totalTimeA - totalTimeB;
						// if you want to change the sort order, simply use the following:
						// return -valA.compareTo(valB);
					}
				});

			} else if (priority == 2) {

				Collections.sort(jsonValues, new Comparator<JSONObject>() {
					// You can change "Name" with "ID" if you want to sort by ID

					@Override
					public int compare(JSONObject a, JSONObject b) {
						int transitA = 0;
						int transitB = 0;

						try {
							JSONObject infoA = (JSONObject) a.get("info");
							JSONObject infoB = (JSONObject) b.get("info");

							transitA += Integer.parseInt(String.valueOf(infoA.get("busTransitCount")));
							transitA += Integer.parseInt(String.valueOf(infoA.get("subwayTransitCount")));

							transitB += Integer.parseInt(String.valueOf(infoB.get("busTransitCount")));
							transitB += Integer.parseInt(String.valueOf(infoB.get("subwayTransitCount")));
						} catch (JSONException e) {
							// do something
						}

						return transitA - transitB;
						// if you want to change the sort order, simply use the following:
						// return -valA.compareTo(valB);
					}
				});
			}

		}

		for (int i = 0; i < jsonValues.size(); i++) {
			sortedJsonArray.add(jsonValues.get(i));
		}

		if (favorite == 0 && priority == 0) {
			sortedJsonArray = path;
		}

		return sortedJsonArray;
	}

	// 시간 계산 (type=1 : tmpTime시간 후, type=2 : tmpTime시간 전)
	public String CalculateTime(String nowTime, int tmpTime, int type) {
		String[] time = nowTime.split(":");
		int hour = Integer.parseInt(time[0]);
		int minute = Integer.parseInt(time[1]);
		String resultTime = "";
		if (type == 1) {
			resultTime = (hour * 60 + minute + tmpTime) / 60 + ":" + (hour * 60 + minute + tmpTime) % 60;
		} else if (type == 2) {
			resultTime = (hour * 60 + minute - tmpTime) / 60 + ":" + (hour * 60 + minute - tmpTime) % 60;
		}
		return resultTime;
	}

	// 지하철 타임테이블
	@Override
	public String TimeTableSubway(int stationID, int wayCode, String startTime) {

		final String openUrl = "https://api.odsay.com/v1/api/subwayTimeTable?lang=0&stationID=" + stationID + "&wayCode=" + wayCode + "&apiKey=" + apiProperties.getKey();

		String realStartTime = "";

		try {

			URL url = new URL(openUrl);
			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONObject OrdList = (JSONObject) response.get("OrdList");
			JSONArray time = new JSONArray();

			// 상행, 하행
			if (wayCode == 1) {
				JSONObject up = (JSONObject) OrdList.get("up");
				time = (JSONArray) up.get("time");
			} else if (wayCode == 2) {
				JSONObject down = (JSONObject) OrdList.get("down");
				time = (JSONArray) down.get("time");
			}

			// 시간 시,분 계산
			String[] tmpTime = startTime.split(":");
			int hour = Integer.parseInt(tmpTime[0]);
			int minute = Integer.parseInt(tmpTime[1]);

			// 세부 경로들 계산
			for (int i = 0; i < time.size() - 1; i++) {
				JSONObject smallTime = (JSONObject) time.get(i);
				int subwayHour = Integer.parseInt(String.valueOf(smallTime.get("Idx")));

				// 버스 시간표 hour과 같을 때, 최소 차이의 minute 구하기
				if (hour == subwayHour) {
					String[] subwayMinute = String.valueOf(smallTime.get("list")).split(" ");
					int min = Integer.MAX_VALUE;

					for (int j = 0; j < subwayMinute.length; j++) {
						int intSubwayMinute = Integer.parseInt(subwayMinute[j].substring(0, 2));
						if (minute >= intSubwayMinute) {
							min = Math.min(min, minute - intSubwayMinute);
						}
					}

					realStartTime = CalculateTime(startTime, min, 2);
					break;
				}

			}

			urlConnection.disconnect();

		} catch (Exception e) {
			logger.error("지하철 실시간 계산 실패 : {}", e);
		}
		return realStartTime;
	}


	// 버스 실시간 위치정보
	@Override
	public String RealTimeBus(int busID, int startBusStationId, String startTime) {

		ArrayList<Integer> busDirTime = BusStationTime(busID, startBusStationId);

		String realStartTime = "";

		// 시간 시,분 계산
		String[] tmpTime = startTime.split(":");
		int hour = Integer.parseInt(tmpTime[0]);
		int minute = Integer.parseInt(tmpTime[1]);


		Calendar cal = Calendar.getInstance();
		int nowHour = cal.get(Calendar.HOUR_OF_DAY);
		int nowMin = cal.get(Calendar.MINUTE);
		String nowTime = nowHour + ":" + nowMin;

		// 실시간 버스 위치 정보 조회
		final String openUrl = "https://api.odsay.com/v1/api/realtimeRoute?lang=0&busID=" + busID + "&apiKey=" + apiProperties.getKey();

		try {

			URL url = new URL(openUrl);
			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray real = (JSONArray) response.get("real");

			for (int i = 0; i < real.size() - 1; i++) {

				JSONObject realBus = (JSONObject) real.get(i);

				int fromStationIdx = Integer.parseInt(String.valueOf(realBus.get("fromStationSeq")));
				// 현재 목적지 정류장 idx보다 뒤쪽에 있는 버스라면
				if (startBusStationIdx + 1 < fromStationIdx) {
					int busTimeSum = 0;
					// 뒤쪽에 있는 버스가 현재 목적지 정류장에 도착할 시간 구하기
					for (int j = startBusStationIdx; j < fromStationIdx; j++) {
						busTimeSum += busDirTime.get(j);
					}

					String busTmpTime = CalculateTime(nowTime, busTimeSum, 1);

					String[] busRealTime = busTmpTime.split(":");
					int realHour = Integer.parseInt(busRealTime[0]);
					int realMinute = Integer.parseInt(busRealTime[1]);
					if ((hour * 60 + minute) >= (realHour * 60 + realMinute)) {
						realStartTime = busTmpTime;
						break;
					}
					// 도착한 시간이 현재 출발해야 하는 시간보다 늦다면
				}
			}

			urlConnection.disconnect();

		} catch (Exception e) {
			logger.error("버스 실시간 위치정보 계산 실패 : {}", e);
		}
		return realStartTime;


	}


	// 버스 정류장 사이 별 소요시간
	public ArrayList<Integer> BusStationTime(int busID, int startBusStationId) {

		// 정류장 사이별 소요시간 list
		ArrayList<Integer> busDirTime = new ArrayList<Integer>();
		startBusStationIdx = 0;

		// 버스노선 상세정보 조회
		final String openUrl = "https://api.odsay.com/v1/api/busLaneDetail?lang=0&busID=" + busID + "&apiKey=" + apiProperties.getKey();


		try {

			URL url = new URL(openUrl);
			HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
			urlConnection.setRequestMethod("GET");

			BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

			JSONParser parser = new JSONParser();
			JSONObject obj = (JSONObject) parser.parse(br);
			JSONObject response = (JSONObject) obj.get("result");
			JSONArray station = (JSONArray) response.get("station");

			for (int i = 0; i < station.size() - 1; i++) {
				JSONObject smallStation1 = (JSONObject) station.get(i);
				JSONObject smallStation2 = (JSONObject) station.get(i + 1);

				int distance1 = Integer.parseInt(String.valueOf(smallStation1.get("stationDistance")));
				int distance2 = Integer.parseInt(String.valueOf(smallStation2.get("stationDistance")));

				int stationId = Integer.parseInt(String.valueOf(smallStation1.get("stationID")));

				if (stationId == startBusStationId) {
					startBusStationIdx = Integer.parseInt(String.valueOf(smallStation1.get("idx")));
				}

				// 정류장 별 거리차이를 m-> km로 변환
				double diffDistance = (double) (distance2 - distance1) / 1000;

				// 버스 속력 20km/h 로 가정, 반올림 하여 시간 계산
				int diffTime = (int) Math.ceil(diffDistance * 3);
				busDirTime.add(diffTime);
			}

			urlConnection.disconnect();

		} catch (Exception e) {
			logger.error("버스 정류장 사이 별 소요시간 계산 실패 : {}", e);
		}
		return busDirTime;
	}

	@Override
	public ResponseEntity<String> registerRoute(GroupAlarmRegisterRequest groupAlarmRegisterRequest) {
		HttpStatus status = null;
		String result = "등록 미완료";

		List<String> userList = groupAlarmRegisterRequest.getUids();

		try {
			GroupAlarm groupAlarm = groupAlarmDao.findGroupAlarmByGroupalarmidUid(userList.get(0));

			for (String user : userList) {
				LocationId locationId = new LocationId(1, user); // 무조건 집에서 출발한다고 가정
				Location userLocation = locationDao.findLocationByLocationid(locationId);

				String openUrl = "https://api.odsay.com/v1/api/searchPubTransPathT?lang=0&SX=" + userLocation.getLatitude() + "&SY=" + userLocation.getLongitude() + "&EX="
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
				route.setInputtime(groupAlarmRegisterRequest.getInputtime());
				route.setTimetype(1);

				if (!groupAlarmRegisterRequest.getAlarmName().isEmpty()) {
					route.setAlarmname(groupAlarmRegisterRequest.getAlarmName());
				}

				String startTime = new String();
				JSONObject resultObject = new JSONObject();

				// 현재시간
				Calendar today = Calendar.getInstance();
				int nowHour = today.get(Calendar.HOUR_OF_DAY);
				int nowMin = today.get(Calendar.MINUTE);

				URL url = new URL(openUrl);

				HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
				urlConnection.setRequestMethod("GET");

				BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

				JSONParser parser = new JSONParser();
				JSONObject obj = (JSONObject) parser.parse(br);
				JSONObject response = (JSONObject) obj.get("result");
				JSONArray path = (JSONArray) response.get("path");

				// 회원가입되있는 사용자라면 커스텀 반영
				path = CheckCustom(path, user);

				// 세부 경로들 계산
				loop: for (int i = 0; i < path.size(); i++) {
					JSONObject infos = (JSONObject) path.get(i);

					// step1.경로 전체 소요시간 구하기
					JSONObject info = (JSONObject) infos.get("info");
					int totalTime = Integer.parseInt(String.valueOf(info.get("totalTime")));

					String[] arriveTime = groupAlarmRegisterRequest.getArriveTime().split(" ");

					// step2.실시간 반영 전 출발시간(도착시간-소요시간)구하기
					startTime = CalculateTime(arriveTime[1], totalTime, 2);
					resultObject = infos;

					String[] start = startTime.split(":");
					int startHour = Integer.parseInt(start[0]);
					int startMinute = Integer.parseInt(start[1]);

					// step3.(실시간 반영전 출발시간 - 현재시간)이 30분 이하일 경우에만 실시간 출발시간 체크
					if ((startHour * 60 + startMinute) - (nowHour * 60 + nowMin) <= 30) {

						// 첫 대중교통의 실시간 정보 반영한 출발시간 구하기
						JSONArray subPath = (JSONArray) infos.get("subPath");
						int walkTime = 0;

						for (int j = 0; j < subPath.size(); j++) {
							JSONObject smallSubPath = (JSONObject) subPath.get(j);

							// trafficType 1:지하철, 2:버스, 3:도보
							long trafficType = (Long) smallSubPath.get("trafficType");

							// 지하철 운행시간 검색
							if (trafficType == 1) {
								int stationID = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
								int wayCode = Integer.parseInt(String.valueOf(smallSubPath.get("wayCode")));
								String tmpTime = CalculateTime(startTime, walkTime, 1);
								String realStartTime = CalculateTime(TimeTableSubway(stationID, wayCode, tmpTime), walkTime, 2);
								startTime = realStartTime;
								break loop;
							}

							// 버스 실시간 검색
							else if (trafficType == 2) {
								int startBusStationId = Integer.parseInt(String.valueOf(smallSubPath.get("startID")));
								JSONArray lane = (JSONArray) smallSubPath.get("lane");
								JSONObject smallLane = (JSONObject) lane.get(0);
								int busID = Integer.parseInt(String.valueOf(smallLane.get("busID")));
								String tmpTime = CalculateTime(startTime, walkTime, 1);
								String realStartTime = CalculateTime(RealTimeBus(busID, startBusStationId, tmpTime), walkTime, 2);
								startTime = realStartTime;
								break loop;
							}

							// 도보일경우 다음 대중교통 체크
							else if (trafficType == 3) {
								int sectionTime = Integer.parseInt(String.valueOf(smallSubPath.get("sectionTime")));
								walkTime += sectionTime;
								continue;
							}

						}
					}
				}

				/* 결과 출발 시간 만들기 */
				StringBuilder sb = new StringBuilder();
				String[] arriveTime = groupAlarmRegisterRequest.getArriveTime().split(" ");

				sb.append(arriveTime[0] + " "); // 년-월-날
				sb.append(startTime);// 시간

				Date date = new SimpleDateFormat("yyyy-MM-dd hh:mm").parse(sb.toString());
				Calendar lastDate = Calendar.getInstance();
				lastDate.setTime(date);

				long remainSecond = (lastDate.getTimeInMillis() - today.getTimeInMillis()) / 1000;

				route.setArrivetime(sb.toString());
				route.setRouteinfo(resultObject.toString());
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
