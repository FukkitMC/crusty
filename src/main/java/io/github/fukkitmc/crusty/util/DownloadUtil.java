package io.github.fukkitmc.crusty.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import io.github.fukkitmc.crusty.util.Clock;
import org.gradle.api.logging.Logger;

public class DownloadUtil {
	public static Result read(URL url, String etag, long currentLastModifyDate, Logger logger, boolean offline, boolean compressed) throws IOException {
		Clock clock = new Clock("Validating/Downloading " + url + " cache took %dms", logger);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		// If the output already exists we'll use it's last modified time
		if (currentLastModifyDate != -1) {
			if(offline) {
				clock.close();
				return null;
			}
			connection.setIfModifiedSince(currentLastModifyDate);
		}

		if (etag != null) {
			connection.setRequestProperty("If-None-Match", etag);
		}


		if(compressed) {
			// We want to download gzip compressed stuff
			connection.setRequestProperty("Accept-Encoding", "br"); // todo maybe use brotili idk
		}

		// Try make the connection, it will hang here if the connection is bad
		connection.connect();

		int code = connection.getResponseCode();

		if ((code < 200 || code > 299) && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
			//Didn't get what we expected
			clock.close();
			throw new IOException(connection.getResponseMessage() + " for " + url);
		}

		long modifyTime = connection.getHeaderFieldDate("Last-Modified", -1);

		if (currentLastModifyDate != -1 && (code == HttpURLConnection.HTTP_NOT_MODIFIED || modifyTime > 0 && currentLastModifyDate >= modifyTime)) {
			if(logger != null) logger.lifecycle("'{}' Not Modified, skipping.", url);
			clock.close();
			return null; //What we've got is already fine
		}

		long contentLength = connection.getContentLengthLong();

		if (contentLength >= 0) {
			if(logger != null) logger.lifecycle("'{}' Changed, downloading {}", url, toNiceSize(contentLength));
		}

		try { // Try download to the output
			InputStream stream = connection.getInputStream();
			return new Result(stream, modifyTime, clock, connection.getHeaderField("ETag"));
		} catch (IOException e) {
			clock.close();
			throw e;
		}
	}


	public static class Result {
		public final InputStream stream;
		public final long lastModifyDate;
		public final Clock clock;
		public final String etag;

		public Result(InputStream stream, long date, Clock clock, String etag) {
			this.stream = stream;
			this.lastModifyDate = date;
			this.clock = clock;
			this.etag = etag;
		}
	}

	public static String toNiceSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		} else if (bytes < 1024 * 1024) {
			return bytes / 1024 + " KB";
		} else if (bytes < 1024 * 1024 * 1024) {
			return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
		} else {
			return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
	}
}