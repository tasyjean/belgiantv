package services;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import models.ImdbApiMovie;
import play.Logger;
import play.libs.WS;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

public class ImdbApiService {
	
	public ImdbApiMovie findOrRead(final String title){
		return findOrRead(title, null);
	}

	public ImdbApiMovie findOrRead(final String title, final Integer year){
		if(title==null){
			throw new IllegalArgumentException("NULL title");
		}
		ImdbApiMovie imdbMovie = ImdbApiMovie.findByTitleAndYear(title, year);
		if(imdbMovie == null){
			String url = apiUrl(title, year);
			Logger.info(url);
			try{
				JsonElement json = WS.url(url).get().getJson();
				Gson gson = new Gson();
				imdbMovie = gson.fromJson(json, ImdbApiMovie.class);
			}catch(Exception ex){
				Logger.error(ex.getMessage(), ex);
			}
			imdbMovie.save();
		}
		return imdbMovie;
	}

	private String apiUrl(final String title, final Integer year) {
		String url = null;
		try {
			url = "http://www.imdbapi.com/?t=" + URLEncoder.encode(title, "UTF-8");
			if(year != null){
				url = url + "&y=" +year;
			}
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		return url;
	}
}
