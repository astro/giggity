package net.gaast.giggity;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import org.apache.commons.io.input.TeeInputStream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

/** Caching HTTP fetcher. */
public class Fetcher {
	private Giggity app;
	private File fn, fntmp = null;
	private URLConnection dlc;
	private BufferedReader in;
	private Source source;
	private Handler progressHandler;
	private long flen;
	
	public enum Source {
		CACHE,			/* Get from cache or fail. */
		CACHE_ONLINE,	/* Get from cache, allow fetch if not available. */
		ONLINE_CACHE,	/* Check online if we can, otherwise use cache. */
		ONLINE,			/* Check online (304 -> cache). */
		ONLINE_NOCACHE,	/* Fetch online, ignore cached version. */
	}
	
	public Fetcher(Giggity app_, String url, Source prefSource) throws IOException {
		app = app_;
		source = null;

		Log.d("Fetcher", "Creating fetcher for " + url + " prefSource=" + prefSource);
		
		fn = new File(app.getCacheDir(), "sched." + Schedule.hashify(url));
		fntmp = null;

		NetworkInfo network = ((ConnectivityManager)
				app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo(); 
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(app);

		if ((prefSource == Source.ONLINE || prefSource == Source.ONLINE_NOCACHE) &&
		    (network == null || !network.isConnected())) {
			throw new IOException("No network connection available.");
		}
		
		URL dl = new URL(url);
		dlc = dl.openConnection();
		
		try {
			/* Do HTTP stuff first, then HTTPS! Once we get a CastClassException, we stop. */
			HttpURLConnection.setFollowRedirects(true);
			
			/* Disabled for now since I can't get the default User-Agent until after sending it.
			String ua = ((HttpURLConnection)dlc).getRequestProperty("User-Agent");
			((HttpURLConnection)dlc).setRequestProperty("User-Agent", "Giggity, " + ua);
			*/
			
			((HttpURLConnection)dlc).addRequestProperty("Accept-Encoding", "gzip");
			
			if (prefSource != Source.ONLINE_NOCACHE && fn.canRead() && fn.lastModified() > 0) {
				/* TODO: Not sure if it's a great idea to use inode metadata to store
				 * modified-since data, but it works so far.. */
				dlc.setIfModifiedSince(fn.lastModified());
			}
			
			if (!pref.getBoolean("strict_ssl", false))
				((HttpsURLConnection)dlc).setSSLSocketFactory(SSLRage.getSocketFactory());
		} catch (ClassCastException e) {
			/* It failed. Maybe we're HTTP only? Maybe even FTP? */
		}

		if (prefSource != Source.CACHE && !(fn.canRead() && prefSource == Source.CACHE_ONLINE) &&
		    network != null && network.isConnected()) {
			int status;
			try {
				status = ((HttpURLConnection)dlc).getResponseCode();
				Log.d("Fetcher", "HTTP status "+status);
			} catch (ClassCastException e) {
				/* Assume success if this isn't HTTP.. */
				status = 200;
			}
			if (status == 200) {
				fntmp = new File(app.getCacheDir(), "tmp." + Schedule.hashify(url));
				OutputStream copy = new FileOutputStream(fntmp);
				Reader rawin;
				String enc = dlc.getContentEncoding();
				
				InputStream is = dlc.getInputStream();
				if (dlc.getContentLength() > -1) {
					flen = dlc.getContentLength();
					is = new ProgressStream(is);
				}
				if (enc != null && enc.contains("gzip")) {
					is = new GZIPInputStream(is);
				}

				is = new TeeInputStream(is, copy, true);
				in = new BufferedReader(new InputStreamReader(is, "utf-8"));
				source = Source.ONLINE;
			} else if (status == 304) {
				Log.i("Fetcher", "HTTP 304, using cached copy");
				source = Source.ONLINE; /* We're reading cache, but 304 means it should be equivalent. */
				/* Just continue, in = null so we'll read from cache. */
			} else {
				throw new IOException("Download error: " + dlc.getHeaderField(0));
			}
		}

		if (in == null && fn.canRead()) {
			/* We have no download stream and should use the cached copy (i.e. we're offline). */
			flen = fn.length();
			in = new BufferedReader(new InputStreamReader(new ProgressStream(new FileInputStream(fn)), "utf-8"));
			dlc = null;
			if (source != Source.ONLINE)
				source = Source.CACHE;
		} else if (in == null) {
			throw new IOException("No network connection or cached copy available.");
		}
	}
	
	public void setProgressHandler(Handler handler) {
		progressHandler = handler;
	}
	
	public BufferedReader getReader() {
		return in;
	}
	
	public String slurp() {
		String ret = "";
		char[] buf = new char[1024];
		int n;
		
		try {
			while ((n = in.read(buf, 0, buf.length)) > 0)
				ret += new String(buf, 0, n);
		} catch (IOException e) {
			e.printStackTrace();
			ret = null;
		}
		return ret;
	}
	
	public Source getSource() {
		return source;
	}

	/** If the file is usable, keep it cached. */
	public void keep() {
		try {
			/* Close it now because otherwise (I'm just guessing) we may still flush a buffer and reset mtime. */
			in.close();
		} catch (IOException e) {
		}
		
		if (fntmp != null)
			/* Save the cached copy (overwrite the old one, if any). */
			fntmp.renameTo(fn);
		
		/* Store last-modified date so we can cache more efficiently. */
		if (dlc != null && dlc.getLastModified() > 0)
			fn.setLastModified(dlc.getLastModified());
	}
	
	/** If the file was corrupt in any way, remove it. */
	public void cancel() {
		if (fntmp != null)
			/* Delete the cached copy we were writing. */
			fntmp.delete();
		else
			/* Delete the cached copy we were using. */
			fn.delete();
	}

	private class ProgressStream extends InputStream {
		private InputStream in;
		private boolean waiting;
		private long offset;

		public ProgressStream(InputStream in_) {
			in = in_;
		}
		
		@Override
		public int read() throws IOException {
			if (!waiting)
				offset++;
			return in.read();
		}
		
		@Override
		public int read(byte b[], int off, int len) throws IOException {
			int ret = in.read(b, off, len);
			if (!waiting) {
				offset += ret;
				if (progressHandler != null && flen > 0 && ret > 0) {
					int prog = (int) (100L * offset / flen);
					if (prog > 0)
						progressHandler.sendEmptyMessage((int) (100L * offset / flen));
				}
			}
			return ret;
		}
		
		@Override
		public int read(byte b[]) throws IOException {
			return read(b, 0, b.length);
		}
		
		@Override
		public void mark(int limit) {
			in.mark(limit);
			waiting = true;
		}
		
		@Override
		public void reset() throws IOException {
			in.reset();
			waiting = false;
		}
	}
}
