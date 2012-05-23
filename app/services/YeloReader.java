package services;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import models.ImdbApiMovie;
import models.Movie;
import models.YeloTVGids;
import models.YeloTVGids.Broadcast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import play.Logger;
import play.libs.WS;
import play.libs.WS.HttpResponse;
import play.libs.WS.WSRequest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

public class YeloReader {

	private static final String BASE_URI = "http://yelo.be/";

	public List<Movie> read(final Date date){
    	
    	List<Movie> movies = new ArrayList<Movie>();
    	movies.addAll(readDay(date));
    	movies.addAll(readChannel("jim", date));
    	movies.addAll(readChannel("ned1", date));
    	movies.addAll(readChannel("ned2", date));
    	movies.addAll(readChannel("ned3", date));
    	
    	for(Movie movie:movies){
    		if(!sameDay(movie.start, date)){
    			throw new RuntimeException("Movie returned for wrong day" + movie.start + ", should be " + date);
    		}
    	}
    	
    	return movies;
	}

	private List<Movie> readChannel(final String channel, final Date date) {
		DateFormat dayFormat = new SimpleDateFormat("dd-MM-yyyy");
		String day = dayFormat.format(date);
		String guideUri = BASE_URI + "zenders/"+channel+"?date="+day;
		Document doc = readUri(guideUri);
    	
    	List<Movie> movies = new ArrayList<Movie>();
    	
    	Elements links = doc.select("a[class][href~=(/film/).*");
    	for(Element link:links){
    		Movie movie = Movie.findByUrl(switchToHdWhereProssible(link.absUrl("href")));
    		if(movie == null){
    			movie = parseChannelPageMovie(link);
    			//movie.year = getYear(movie.url);
    			movie.save();
    		}
    		movies.add(movie);
    	}
		return movies;
	}
	
	private List<Movie> readDay(final Date date) {
		List<Movie> movies = new ArrayList<Movie>();
		movies.addAll(readDayPart(date, 1));
		movies.addAll(readDayPart(date, 2));
		return movies;
	}
	
	private List<Movie> readDayPart(final Date date, final long part) {
		DateFormat dayFormat = new SimpleDateFormat("dd-MM-yyyy");
		String day = dayFormat.format(date);
		
//		String guideUriAjax = BASE_URI + "tv-gids/groep/vlaams?date=" + day +"&part="+part + "&orientation=vertical";
//		Document ajaxDoc = readUri(guideUriAjax, "application/json, text/javascript, */*");
//		System.err.println(ajaxDoc);
		
		String guideUri = BASE_URI + "detail/tvgids?date="+day;
		JsonElement element = readUriJson(guideUri, null);
		//printJson(element);
		
		Gson gson = new Gson();
		YeloTVGids gids = gson.fromJson(element, YeloTVGids.class);
		for(Entry<String,Broadcast> entry:gids.result.meta.pvrbroadcasts.entrySet()){
			Broadcast broadcast = entry.getValue();
			if(broadcast.is_serie == 0){
				System.out.println(new Date(broadcast.start_time*1000) + "@" + broadcast.channel_webpvr_id + " " + broadcast.title);
			}
		}
		
    	
    	List<Movie> movies = new ArrayList<Movie>();
//    	Element tvgidsDiv = doc.select("div[class=tvgids-lijst box]").first();
//    	Elements links = tvgidsDiv.select("a[href~=(/film/).*");
//    	for(Element link:links){
//    		Movie movie = Movie.findByUrl(switchToHdWhereProssible(link.absUrl("href")));
//    		if(movie == null){
//    			movie = parseGuidePageMovie(link);
//    			movie.year = getYear(movie.url);
//    			movie.save();
//    		}
//    		movies.add(movie);
//    	}
		return movies;
	}

	private void printJson(JsonElement element) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		System.out.println(gson.toJson(element));
	}
	
//	Integer getYear(final String moviePageUrl){
//		Document doc = readUri(moviePageUrl);
////		<span class="filmdata-titel">Jaar: </span>
////		<span class="filmdata-content">2010</span>
//
//		Element yearTitleElement = doc.select("span[class=filmdata-titel]:contains(Jaar:)").first();
//		Element yearElement = yearTitleElement.nextElementSibling();
//		Integer year = null;
//		try{
//			year = Integer.valueOf(yearElement.text());
//		}catch(NumberFormatException ex){
//			Logger.warn("Could not parse year: [%s]", yearElement.text());
//		}
//		return year;
//	}

	
	private Movie parseGuidePageMovie(Element link){
    	DateFormat hourFormat = new SimpleDateFormat("HH:mm");
    	DateFormat dayFormat = new SimpleDateFormat("dd-MM-yyyy");
		Element em = link.siblingElements().select("em").first();
		
		Movie movie = new Movie();
		movie.title = link.text().trim();
		URI movieUri;
		try {
			movieUri = new URI(link.absUrl("href"));
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		Map<String,String> params = splitParameters(movieUri);
		movie.url = switchToHdWhereProssible(movieUri.toString());
		movie.channel = params.get("channel");
		try {
			movie.start = dayFormat.parse(params.get("date"));
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
		try {
			Date hour = hourFormat.parse(em.text());
			movie.start = TimeUtil.merge(movie.start, hour);
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
		return movie;
	}
	
	private Movie parseChannelPageMovie(Element link){
		DateFormat hourFormat = new SimpleDateFormat("HH:mm");
		DateFormat dayFormat = new SimpleDateFormat("dd-MM-yyyy");
		Element em = link.parent().siblingElements().select("span[class=datum]").first();
		
		Movie movie = new Movie();
		URI movieUri;
		try {
			movieUri = new URI(link.absUrl("href"));
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		movie.title = link.attr("title")
				.replace("FILM OP 2: ", "")
				.replace("Z@ppbios: ", "")
				.replace("Sunday Movie: ", "")
				.replace("Saturday Movie: ", "").trim();
		Map<String,String> params = splitParameters(movieUri);
		try {
			movie.start = dayFormat.parse(params.get("date"));
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
		movie.url = switchToHdWhereProssible(movieUri.toString());
		movie.channel = params.get("channel");
		try {
			Date hour = hourFormat.parse(em.text());
			movie.start = TimeUtil.merge(movie.start, hour);
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
		return movie;
	}
	

	private boolean sameDay(Date day1, Date day2) {
		Calendar day1Cal = Calendar.getInstance();
		day1Cal.setTime(day1);
		Calendar day2Cal = Calendar.getInstance();
		day2Cal.setTime(day2);
		return day1Cal.get(Calendar.YEAR) == day2Cal.get(Calendar.YEAR) &&
			day1Cal.get(Calendar.MONTH) == day2Cal.get(Calendar.MONTH) &&
			day1Cal.get(Calendar.DAY_OF_MONTH) == day2Cal.get(Calendar.DAY_OF_MONTH);
	}
	
	private Document readUri(String uri) {
		return this.readUri(uri, null);
	}
	
	private Document readUri(String uri, final String accept) {
		Logger.info(uri);
		WSRequest req = WS.url(uri);
		if(accept != null){
			req.setHeader("accept", accept);
		}
    	HttpResponse response = req.get();
    	String html = response.getString();
    	Document doc = Jsoup.parse(html, BASE_URI);
		return doc;
	}
	
	private JsonElement readUriJson(String uri, final String accept) {
		Logger.info(uri);
		WSRequest req = WS.url(uri);
		if(accept != null){
			req.setHeader("accept", accept);
		}
    	HttpResponse response = req.get();
    	return response.getJson();
	}
	
	private Map<String,String> splitParameters(URI uri){
		Map<String,String> result = new HashMap<String, String>();
		String query = uri.getQuery();
		String[] params = query.split("&");
		for(String param:params){
			String[] keyValue = param.split("=");
			result.put(keyValue[0], keyValue[1]);
		}
		return result;
	}
	
	private ImdbApiMovie readOrFetch(final Movie movie){
		ImdbApiMovie imdbMovie = ImdbApiMovie.findByTitle(movie.title);
		if(imdbMovie != null){
			movie.imdb = imdbMovie;
			movie.save();
		}
		return imdbMovie;
	}
	
	private String switchToHdWhereProssible(final String url){
		return url.replace("channel=een", "channel=een-hd")
				.replace("channel=ketnetcanvas", "channel=ketnetcanvas-hd")
				.replace("channel=vtm", "channel=vtm-hd");
	}
}
