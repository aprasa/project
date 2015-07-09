/*
 * Written and copyright 2001-2003 Tobias Minich.
 * 
 * HTTPDownloader.java
 * 
 * Created on 17. August 2003, 22:22
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.gudy.azureus2.core3.torrentdownloader.impl;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.*;

import org.gudy.azureus2.core3.config.COConfigurationManager;
import org.gudy.azureus2.core3.internat.MessageText;
import org.gudy.azureus2.core3.security.SESecurityManager;
import org.gudy.azureus2.core3.torrentdownloader.TorrentDownloaderCallBackInterface;
import org.gudy.azureus2.core3.torrentdownloader.TorrentDownloader;
import org.gudy.azureus2.core3.util.*;
import org.gudy.azureus2.core3.util.protocol.magnet.MagnetConnection;
import org.gudy.azureus2.core3.util.protocol.magnet.MagnetConnection2;
import org.gudy.azureus2.core3.torrent.*;
import org.gudy.azureus2.plugins.utils.resourcedownloader.ResourceDownloaderException;
import org.gudy.azureus2.pluginsimpl.local.utils.xml.rss.RSSUtils;

import com.aelitis.azureus.core.proxy.AEProxyFactory;
import com.aelitis.azureus.core.proxy.AEProxyFactory.PluginProxy;
import com.aelitis.azureus.core.vuzefile.VuzeFileHandler;


/**
 * @author Tobias Minich
 */
public class TorrentDownloaderImpl extends AEThread implements TorrentDownloader {

  private String	original_url; 
  private String 	url_str;
  private Proxy		proxy;
  private String	referrer;
  private Map		request_properties;
  private String 	file_str;
  
  private URL url;
  private URLConnection con;
  private String error = "Ok";
  private String status = "";
  private TorrentDownloaderCallBackInterface iface;
  private int state = STATE_NON_INIT;
  private int percentDone = 0;
  private int readTotal = 0;
  private boolean cancel = false;
  private String filename, directoryname;
  private File file = null;
  private byte[] buf = new byte[1020];
  private int bufBytes = 0;
  private boolean deleteFileOnCancel = true;
  private boolean ignoreReponseCode = false;
  

  private AEMonitor this_mon 	= new AEMonitor( "TorrentDownloader" );
	private int errCode;

  public TorrentDownloaderImpl() {
    super("Torrent Downloader");
     setDaemon(true);
  }

  public void 
  init(
  		TorrentDownloaderCallBackInterface	_iface, 
		String 								_url,
		Proxy								_proxy,
		String								_referrer,
		Map									_request_properties,
		String								_file )
  {
    this.iface = _iface;
    
    original_url = _url;
    
    //clean up accidental left-facing slashes
    _url = _url.replace( (char)92, (char)47 );
    
    // it's possible that the URL hasn't been encoded (see Bug 878990)
    _url = _url.replaceAll( " ", "%20" );

    setName("TorrentDownloader: " + _url);
    
    url_str 			= _url;
    proxy				= _proxy;
    referrer			= _referrer;
    request_properties	= _request_properties;
    file_str			= _file;
    
    if ( referrer == null || referrer.length() == 0 ){
    	
    	try{
    			// maybe can't do any harm here setting referer - fixes some download issues...
    		
    		referrer = url_str;
    		
    	}catch( Throwable e ){
    	}
    }
  }

  public void notifyListener() {
    if (this.iface != null)
      this.iface.TorrentDownloaderEvent(this.state, this);
    else if (this.state == STATE_ERROR)
      System.err.println(this.error);
  }

  private void cleanUpFile() {
    if ((this.file != null) && this.file.exists())
      this.file.delete();
  }

  private void error(int errCode, String err) {
  	try{
  		this_mon.enter();	// what's the point of this?
  	
  		this.state = STATE_ERROR;
  		this.setError(errCode, err);
  		this.cleanUpFile();
  		this.notifyListener();
  	}finally{
  		
  		this_mon.exit();
  		
  		closeConnection();
  	}
  }

  public void 
  runSupport() {

  	try{
  		new URL( url_str );  //determine if this is already a proper URL
  		
  	}catch( Throwable t ) {  //it's not
  		
  			//check if the string is just a base32/hex-encoded torrent infohash
  		
  		String magnet_uri = UrlUtils.normaliseMagnetURI( url_str );
  		
  		if ( magnet_uri != null ){
  			
  			url_str = magnet_uri;
  		}
  	}
 
    try{      
    	url = AddressUtils.adjustURL( new URL(url_str));
      
    	String	protocol = url.getProtocol().toLowerCase( Locale.US );
	  
    	// hack here - the magnet download process requires an additional paramter to cause it to
    	// stall on error so the error can be reported
	  
    	if ( protocol.equals( "magnet" ) || protocol.equals( "maggot" ) || protocol.equals( "dht" )){
		  
    		url = AddressUtils.adjustURL( new URL(url_str+(url_str.contains("?")?"&":"?") + "pause_on_error=true"));
    	}
	  
		Set<String>	redirect_urls = new HashSet<String>();

    	boolean follow_redirect = true;
    	
    	URL		current_url		= url;
    	Proxy	current_proxy	= proxy;
    	
    	PluginProxy	current_plugin_proxy = AEProxyFactory.getPluginProxy( current_proxy );
    	
 redirect_label:
	 
		while( follow_redirect ){
				
			follow_redirect = false;   	
			
	    	for (int connect_loop=0;connect_loop<2;connect_loop++){
	    		
	    		try{
	
	    			if ( protocol.equals("https")){
	
	    				// see ConfigurationChecker for SSL client defaults
	
	    				HttpsURLConnection ssl_con;
	    				
	    				if ( current_proxy == null ){
	    					
	    					ssl_con = (HttpsURLConnection)current_url.openConnection();
	    					
	    				}else{
	    					
	    					ssl_con = (HttpsURLConnection)current_url.openConnection( current_proxy );
	    				}
	
	    				// allow for certs that contain IP addresses rather than dns names
	
	    				ssl_con.setHostnameVerifier(
	    						new HostnameVerifier()
	    						{
	    							public boolean
	    							verify(
	    									String		host,
	    									SSLSession	session )
	    							{
	    								return( true );
	    							}
	    						});
	
	    				con = ssl_con;
	
	    			}else{
	
	    				if ( current_proxy == null ){
	    					
	    					con = current_url.openConnection();
	    					
	    				}else{
	    					
	    					con = current_url.openConnection( current_proxy );
	    				}
	    			}
	
					if ( con instanceof HttpURLConnection ){
						
							// we want this true but some plugins (grrr) set the global default not to follow
							// redirects
					
						((HttpURLConnection)con).setInstanceFollowRedirects( proxy==null );
					}
					
	    			con.setRequestProperty("User-Agent", Constants.AZUREUS_NAME + " " + Constants.AZUREUS_VERSION);     
	
	    			if ( referrer != null && referrer.length() > 0 ){
	
	    				con.setRequestProperty( "Referer", referrer );
	    			}
	
	    			if ( request_properties != null ){
	
	    				Iterator it = request_properties.entrySet().iterator();
	
	    				while( it.hasNext()){
	
	    					Map.Entry	entry = (Map.Entry)it.next();
	
	    					String	key 	= (String)entry.getKey();
	    					String	value	= (String)entry.getValue();
	
	    					// currently this code doesn't support gzip/deflate...
	
	    					if ( !key.equalsIgnoreCase( "Accept-Encoding" )){
	
	    						con.setRequestProperty( key, value );
	    					}
	    				}
	    			}
	
	    			this.con.connect();
	    			
	    			String magnetURI = con.getHeaderField("Magnet-Uri");
	    			
	    			if ( magnetURI != null ){
	    				
	    				closeConnection();
	    				
	    				url_str = magnetURI;
	    				
	    				runSupport();
	    				
	    				return;
	    			}
	
	    	  		int response = con instanceof HttpURLConnection?((HttpURLConnection)con).getResponseCode():HttpURLConnection.HTTP_OK;
	
					if ( 	response == HttpURLConnection.HTTP_MOVED_TEMP ||
							response == HttpURLConnection.HTTP_MOVED_PERM ){
						
							// auto redirect doesn't work from http to https or vice-versa
						
						String	move_to = con.getHeaderField( "location" );
						
						if ( move_to != null ){
							
							if ( redirect_urls.contains( move_to ) || redirect_urls.size() > 32 ){
								
								break;
							}
							
							redirect_urls.add( move_to );
							
							try{
									// don't URL decode the move-to as its already in the right format!
								
								URL	move_to_url = new URL( move_to ); // URLDecoder.decode( move_to, "UTF-8" ));
								
								boolean	follow = false;
								
								if ( current_plugin_proxy != null ){
									
									PluginProxy child = current_plugin_proxy.getChildProxy( "redirect", move_to_url );
									
									if ( child != null ){
																				
											// use an overall property to force this through on the redirect
										
										request_properties.put( "HOST", child.getURLHostRewrite() + (move_to_url.getPort()==-1?"":(":" + move_to_url.getPort())));
	
										current_proxy	= child.getProxy();
										move_to_url		= child.getURL();	
										
										follow = true;
									}
								}
	
								String	original_protocol 	= current_url.getProtocol().toLowerCase();
								String	new_protocol		= move_to_url.getProtocol().toLowerCase();
								
								if ( follow || !original_protocol.equals( new_protocol )){
									
									current_url = move_to_url;
									
									try{
										List<String>	cookies_list = con.getHeaderFields().get( "Set-cookie" );
										
										List<String>	cookies_set = new ArrayList<String>();
										
										if ( cookies_list != null ){
											
											for (int i=0;i<cookies_list.size();i++){
												
												String[] cookie_bits = ((String)cookies_list.get(i)).split(";");
												
												if ( cookie_bits.length > 0 ){
												
													cookies_set.add( cookie_bits[0] );
												}
											}
										}
										
										if ( cookies_set.size() > 0 ){
											
											String	new_cookies = "";
																					
											Object obj = request_properties.get( "Cookie" );
											
											if ( obj instanceof String ){
											
												new_cookies = (String)obj;
											}
											
											for ( String s: cookies_set ){
												
												new_cookies += (new_cookies.length()==0?"":"; ") + s;
											}
											
											request_properties.put( "Cookie", new_cookies );
										}
									}catch( Throwable e ){
										
										Debug.out( e );
									}
									
									follow_redirect = true;
									
									continue redirect_label;
								}
							}catch( Throwable e ){
								
							}
						}
					}
	    			 			
	    			
	    			break;
	
	    		}catch( SSLException e ){
	
	    			if ( connect_loop == 0 ){
	
	    				if ( SESecurityManager.installServerCertificates( url ) != null ){
	
	    					// certificate has been installed
	
	    					continue;	// retry with new certificate
	    				}
	    			}
	
	    			throw( e );
	
	    		}catch( IOException e ){
	
	    			if ( connect_loop == 0 ){
	
	    				URL retry_url = UrlUtils.getIPV4Fallback( url );
	
	    				if ( retry_url != null ){
	
	    					url = retry_url;
	
	    				}else{
	
	    					throw( e );
	    				}
	    			}
	    			
	    			if ( e instanceof UnknownHostException ){
	    				
	    				throw( e );
	    			}
	    		}
	    	}
		}
      
  		int response = con instanceof HttpURLConnection?((HttpURLConnection)con).getResponseCode():HttpURLConnection.HTTP_OK;
    	if (!ignoreReponseCode) {
        if ((response != HttpURLConnection.HTTP_ACCEPTED) && (response != HttpURLConnection.HTTP_OK)) {
          this.error(response, Integer.toString(response) + ": " + ((HttpURLConnection)con).getResponseMessage());
          return;
        }
    	}

      /*
      Map headerFields = this.con.getHeaderFields();
      
      System.out.println("Header of download of " + url_str);
      for (Iterator iter = headerFields.keySet().iterator(); iter.hasNext();) {
				String s = (String) iter.next();
				System.out.println(s + ":" + headerFields.get(s));
				
			}
	*/
      filename = this.con.getHeaderField("Content-Disposition");
      
      if ((filename!=null) && filename.toLowerCase().matches(".*attachment.*")){ // Some code to handle b0rked servers.
    	  
        while (filename.toLowerCase().charAt(0)!='a'){
        	
          filename = filename.substring(1);
        }
      }
      
      	// see if we can grab the filename directly (thanks Angel)
      
      Pattern p = Pattern.compile(".*filename=\\\"(.*)\\\"");
      
      Matcher m = null;
      
      if ( filename != null && ((m = p.matcher( filename )) != null) && m.matches()){
    	  
           filename = m.group(1).trim();
           
      }else if (	filename == null || 
    		  		!filename.toLowerCase().startsWith("attachment") || 
    		  		filename.indexOf('=') == -1 ) {
    	  
        String tmp = this.url.getFile();
        
        if ( tmp.length() == 0 || tmp.equals("/")){
        	
        	filename = url.getHost();
        	
        }else if ( tmp.startsWith("?")){
        
        	// probably a magnet URI - use the hash
        	// magnet:?xt=urn:sha1:VGC53ZWCUXUWVGX7LQPVZIYF4L6RXSU6
        	
       	
        	String	query = tmp.toUpperCase();
        		
    		int	pos = query.indexOf( "XT=URN:SHA1:");
    		
    		if ( pos == -1 ){
    			
    	   		pos = query.indexOf( "XT=URN:BTIH:");		
    		}
    		
    		if ( pos != -1 ){
    			
    			pos += 12;
    			
    			int	p2 = query.indexOf( "&", pos );
    			
    			if ( p2 == -1 ){
    				
    				filename = query.substring(pos);
    				
    			}else{
    				
    				filename = query.substring(pos,p2);
    			}
        	}else{
        		
        		filename = "Torrent" + (long)(Math.random()*Long.MAX_VALUE);
        	}
    		
    		
    		filename += ".tmp";
    		
        }else{
        		// might be /sdsdssd/ffgfgffgfg/ so remove trailing /
        	
        	while( tmp.endsWith( "/" )){
        		
        		tmp = tmp.substring(0,tmp.length()-1);
        	}
        	
	        if (tmp.lastIndexOf('/') != -1){
	         
	        	tmp = tmp.substring(tmp.lastIndexOf('/') + 1);
	        }
	        
	        	// remove any params in the url
	        
	        int	param_pos = tmp.indexOf('?');
	        
	        if ( param_pos != -1 ){
	        	tmp = tmp.substring(0,param_pos);
	        }
	        
	        filename = URLDecoder.decode(tmp, Constants.DEFAULT_ENCODING );
	        
	        if ( filename.length() == 0 ){
	        	
	        	filename = "Torrent" + (long)(Math.random()*Long.MAX_VALUE);
	        }
        }
      } else {
        filename = filename.substring(filename.indexOf('=') + 1);
        if (filename.startsWith("\"") && filename.endsWith("\""))
          filename = filename.substring(1, filename.lastIndexOf('\"'));
        
        filename = URLDecoder.decode(filename, Constants.DEFAULT_ENCODING );
        
        	// this code removes any parent directories from the filename we've extracted
        
        File temp = new File(filename);
        filename = temp.getName();
      }

      filename = FileUtil.convertOSSpecificChars( filename, false );
      
      directoryname = COConfigurationManager.getDirectoryParameter("General_sDefaultTorrent_Directory");
      boolean useTorrentSave = COConfigurationManager.getBooleanParameter("Save Torrent Files");

      if (file_str != null) {
      	// not completely sure about the whole logic in this block
        File temp = new File(file_str);

        //if we're not using a default torrent save dir
        if (!useTorrentSave || directoryname.length() == 0) {
          //if it's already a dir
          if (temp.isDirectory()) {
            //use it
            directoryname = temp.getCanonicalPath();
          }
          //it's a file
          else {
            //so use its parent dir
            directoryname = temp.getCanonicalFile().getParent();
          }
        }

        //if it's a file
        if (!temp.isDirectory()) {
          //set the file name
          filename = temp.getName();
        }
      }
      // what would happen here if directoryname == null and file_str == null??
      
      this.state = STATE_INIT;
      this.notifyListener();
    } catch (java.net.MalformedURLException e) {
      this.error(0, "Exception while parsing URL '" + url_str + "':" + e.getMessage());
    } catch (java.net.UnknownHostException e) {
      this.error(0, "Exception while initializing download of '" + url + "': Unknown Host '" + e.getMessage() + "'");
    } catch (java.io.IOException ioe) {
      this.error(0, "I/O Exception while initializing download of '" + url + "':" + ioe.toString());
    } catch( Throwable e ){
        this.error(0, "Exception while initializing download of '" + url + "':" + e.toString());   	
    }
    
    if ( this.state == STATE_ERROR ){
    	
    	return;
    }
    
    try{
		final boolean	status_reader_run[] = { true };
    
    	this.state = STATE_START;
      
    	notifyListener();
      
    	this.state = STATE_DOWNLOADING;
      
    	notifyListener();
  
    	if ( con instanceof HttpURLConnection ){
    		
	        Thread	status_reader = 
	        	new AEThread( "TorrentDownloader:statusreader" )
				{
	        		public void
					runSupport()
	        		{
	        			HttpURLConnection http_con = (HttpURLConnection)con;
	        			
	        			boolean changed_status	= false;
	        			String	last_status		= "";
	        			
	        			boolean	sleep = false;
	        			
	        			long	last_progress_update = SystemTime.getMonotonousTime();
	        			
	        			while( true ){
	        				
	        				try{
	        					if ( sleep ){
	        					
	        						Thread.sleep(50);
	        						
	        						sleep = false;
	        					}
	        					
	        					try{
	        						this_mon.enter();
	        						
	        						if ( !status_reader_run[0] ){
	        						
	        							break;
	        						}
	        					}finally{
	        						
	        						this_mon.exit();
	        					}
	        					
	        					String	s = http_con.getResponseMessage();
	        					  
	        					if ( s.equals( last_status )){
	        						
	        						sleep = true;
	        						
	        					}else{
	        						
	        						last_status = s;
	        						
	        						String lc_s = s.toLowerCase();
	        						
	        						if ( !lc_s.startsWith("error:")){
	        							
	        							if ( s.toLowerCase().indexOf( "alive" ) != -1 ){
	        								
	        								if ( percentDone < 10 ){
	        									
	        									percentDone++;
	        								}
	        							}
	        							
	        							boolean progress_update = false;
	        							
	        	     					int	pos = s.indexOf( '%' );
	                					
	                					if ( pos != -1 ){
	                						
	                						int	 i;
	                						
	                						for ( i=pos-1;i>=0;i--){
	                							
	                							char	c = s.charAt(i);
	                							
	                							if ( !Character.isDigit( c ) && c != ' ' ){
	                								
	                								i++;
	                								
	                								break;
	                							}
	                						}
	                						
	                						try{
	                							percentDone = Integer.parseInt( s.substring( i, pos ).trim());
	                							
	                							progress_update = true;
	                							
	                						}catch( Throwable e ){
	                							
	                						}
	                					}
	                					
	                					if ( lc_s.startsWith("received")){
	                						
	                						progress_update = true;
	                					}
	                					
	                					if ( progress_update ){
	                						
	                						long now = SystemTime.getMonotonousTime();
	                						
	                						if ( now - last_progress_update < 250 ){
	                							
	                							continue;
	                						}
	                					
	                						last_progress_update = now;
	                					}
	                					
	        							setStatus(s);
	        						}else{
	        							
	        							error(http_con.getResponseCode(), s.substring(6));
	        						}
	        						
	        						changed_status	= true;
	        					} 
	        				}catch( Throwable e ){
	        					
	        					break;
	        				}
	        			}
	        			
	        			if ( changed_status ){
	        				
	        				setStatus( "" );
	        			}
	        		}
				};
				
			status_reader.setDaemon( true );
			
			status_reader.start();
    	}
    	
		InputStream 		in		= null;
		FileOutputStream 	fileout	= null;
		
		try{
			try{
				in = this.con.getInputStream();
			
			} catch (FileNotFoundException e) {
				if (ignoreReponseCode) {
	
					if (con instanceof HttpURLConnection) {
						in = ((HttpURLConnection)con).getErrorStream();
					} else {
						in = null;
					}
				} else {
	
					throw e;
				}
					
			}finally{
				
				try{ 
					this_mon.enter();
						
					status_reader_run[0]	= false;
					
				}finally{
						
					this_mon.exit();
				}
			}
				
				// handle some servers that return gzip'd torrents even though we don't request it!
			
			String encoding = con.getHeaderField( "content-encoding");
				
			if ( encoding != null ){
	
				if ( encoding.equalsIgnoreCase( "gzip" )){
	
					in = new GZIPInputStream( in );
	
				}else if ( encoding.equalsIgnoreCase( "deflate" )){
	
					in = new InflaterInputStream( in );
				}
			}
			
		    if ( this.state != STATE_ERROR ){
			    	
		    	this.file = new File(this.directoryname, filename);
	
		    	boolean useTempFile = file.exists();
		    	if (!useTempFile) {
	  	    	try {
	  	    		this.file.createNewFile();
	  	    		useTempFile = !this.file.exists();
	  	    	} catch (Throwable t) {
	  	    		useTempFile = true;
	  	    	}
		    	}
		    	
		    	if (useTempFile) {
		    		this.file = File.createTempFile("AZU", ".torrent", new File(
								this.directoryname));
		    		this.file.createNewFile();
		    	}
		        
		        fileout = new FileOutputStream(this.file, false);
		        
		        bufBytes = 0;
		        
		        int size = (int) UrlUtils.getContentLength(con);
		        
				this.percentDone = -1;
				
		        do {
		          if (this.cancel){
		            break;
		          }
		          
		          try {
		          	bufBytes = in.read(buf);
		            
		            this.readTotal += bufBytes;
		            
		            if (size > 0){
		              this.percentDone = (100 * this.readTotal) / size;
		            }
		            
		            notifyListener();
		            
		          } catch (IOException e) {
		          }
		          
		          if (bufBytes > 0){
		            fileout.write(buf, 0, bufBytes);
		          }
		        } while (bufBytes > 0);
		        
		        in.close();
		        
		        fileout.flush();
		        
		        fileout.close();
		        
		        if (this.cancel) {
		          this.state = STATE_CANCELLED;
		          if (deleteFileOnCancel) {
		          	this.cleanUpFile();
		          }
		        } else {
		          if (this.readTotal <= 0) {
		            this.error(0, "No data contained in '" + this.url.toString() + "'");
		            return;
		          }
		          
		          	// if the file has come down with a not-so-useful name then we try to rename
		          	// it to something more useful
		          
		          try{
		        	  if ( !filename.toLowerCase().endsWith(".torrent" )){
	
		        		  TOTorrent	torrent = TorrentUtils.readFromFile( file, false );
	
		        		  String	name = TorrentUtils.getLocalisedName( torrent ) + ".torrent";
	
		        		  File	new_file	= new File( directoryname, name );
	
		        		  if ( file.renameTo( new_file )){
	
		        			  filename	= name;
	
		        			  file	= new_file;
		        		  }
		        	  }
		          }catch( Throwable e ){
	
		        	  boolean is_vuze_file = false;
	
		        	  try{
		        		  if ( filename.toLowerCase().endsWith( ".vuze" )){
	
		        			  is_vuze_file = true;
		        			  
		        		  }else{
		        			  
		        			  if ( VuzeFileHandler.getSingleton().loadVuzeFile( file ) != null ){
	
		        				  is_vuze_file = true;
	
		        				  String	name = filename + ".vuze";
	
		        				  File	new_file	= new File( directoryname, name );
	
		        				  if ( file.renameTo( new_file )){
	
		        					  filename	= name;
	
		        					  file	= new_file;
		        				  }
		        			  }
		        		  }
		        	  }catch( Throwable f ){	          		
		        	  }
	
		        	  if ( !is_vuze_file ){
	
		        		  if ( !RSSUtils.isRSSFeed( file )){
		        		  
		        			  Debug.printStackTrace( e );
		        		  }
		        	  }
		          }
		          
		          	// proxy will report this with the correct URL if active
		          
		          if ( proxy == null ){
		          
		        	  TorrentUtils.setObtainedFrom( file, original_url );
		          }
	
		          this.state = STATE_FINISHED;
		        }
		        this.notifyListener();
		      }
		}finally{
			
			if ( in != null ){
				try{
					in.close();
				}catch( Throwable e ){
				}
			}
			if ( fileout != null ){
				try{
					fileout.close();
				}catch( Throwable e ){
				}
			}
		}
      } catch( Throwable e){
    	  
      	String url_log_string = this.url_str.toString().replaceAll( "\\Q&pause_on_error=true\\E", "" );

        String log_msg = MessageText.getString(
              	"torrentdownload.error.dl_fail",
              	new String[]{ url_log_string , file==null?filename:file.getAbsolutePath(), e.getMessage() });

    	if ( !cancel ){
    		
    		Debug.out( log_msg );
    	}
      		
        this.error(	0, log_msg );
      }
  }

  public boolean 
  equals(Object obj) 
  {
    if (this == obj){
    	
      return true;
    }
    
    if ( obj instanceof TorrentDownloaderImpl ){
    	
      TorrentDownloaderImpl other = (TorrentDownloaderImpl) obj;
      
      	// possible during init that url is not yet assigned as async so use original_url as this is more likely
      	// to be set!
      
      if ( other.original_url.equals( this.original_url )){
    	  
    	  File	other_file 	= other.getFile();
    	  File	this_file	= file;
    	  
    	  if ( other_file == this_file ){
    		  
    		  return( true );
    	  }
    	  
    	  if ( other_file == null || this_file == null ){
    		  
    		  return( false );
    	  }
    	  
    	  return( other_file.getAbsolutePath().equals(this_file.getAbsolutePath()));
    	  
      	}else{
      
      		return false;
      	}
    }else{
    	return false;
    }
  }

  
  public int hashCode() {  return this.original_url.hashCode();  }
  
  
  
  public String getError() {
    return this.error;
  }

  public void setError(int errCode, String err) {
    this.error = err;
    this.errCode = errCode;
  }
  
  public int getErrorCode() {
  	return errCode;
  }

  protected void
  setStatus(
  	String	str )
  {
  	status	= str;
  	notifyListener();
  }
  
  public String
  getStatus()
  {
  	return( status );
  }
  
  public java.io.File getFile() {
    if ((!this.isAlive()) || (this.file == null))
      this.file = new File(this.directoryname, filename);
    return this.file;
  }

  public int getPercentDone() {
    return this.percentDone;
  }

  public int getDownloadState() {
    return this.state;
  }

  public void setDownloadState(int state) {
    this.state = state;
  }

  public String getURL() {
    return this.url.toString();
  }

  public void cancel() {
    this.cancel = true;
    closeConnection();
  }

  protected void
  closeConnection()
  {
	if ( con instanceof MagnetConnection ){
	  	((MagnetConnection)con).disconnect();

	} else if ( con instanceof MagnetConnection2 ){
	  	((MagnetConnection2)con).disconnect();

	} else if (con instanceof HttpURLConnection) {
		((HttpURLConnection)con).disconnect();
	}
  }
  
  public void setDownloadPath(String path, String file) {
    if (!this.isAlive()) {
      if (path != null)
        this.directoryname = path;
      if (file != null)
        filename = file;
    }
  }

  /* (non-Javadoc)
   * @see org.gudy.azureus2.core3.torrentdownloader.TorrentDownloader#getTotalRead()
   */
  public int getTotalRead() {
    return this.readTotal;
  }

  public byte[] getLastReadBytes() {
  	if (bufBytes <= 0) {
  		return new byte[0];
  	}
  	byte[] bytes = new byte[bufBytes];
  	System.arraycopy(buf, 0, bytes, 0, bufBytes);
  	return bytes;
  }

  public int getLastReadCount() {
  	return bufBytes;
  }
  
  public void setDeleteFileOnCancel(boolean deleteFileOnCancel) {
  	this.deleteFileOnCancel = deleteFileOnCancel;
  }
  
  public boolean getDeleteFileOnCancel() {
  	return deleteFileOnCancel;
  }

  public boolean isIgnoreReponseCode() {
		return ignoreReponseCode;
	}

	public void setIgnoreReponseCode(boolean ignoreReponseCode) {
		this.ignoreReponseCode = ignoreReponseCode;
	}

}
