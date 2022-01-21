package dcmon;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import com.google.gson.*;

import mail.SendEmail;
import nomad.*;


public class Monitor {

	public static void main(String[] args) throws Exception {
	    //Timer variable for metrics
	    long lstartTime	= System.currentTimeMillis();
    
		//Load properties from config.properties
		Properties prop = new Properties();
	    String configFile;
	    
        //Check if we have command line arg
        if(args.length == 0) {
        	//configFile="C:\\dev\\eclipse-workspace\\DCMon\\src\\dcmon\\config.properties";
        	configFile="config.properties";
        }else {
        	configFile=args[0];
    	}
	    
	    
	    
		try{
		prop.load(new FileInputStream(configFile));
		//prop.load(new FileInputStream("config.properties"));
		}catch(IOException e){
			System.out.println(new Date () + "\t" + "Cannot find " + configFile);
            System.out.println("USAGE");
            System.out.println("java -jar dcMon.jar (no arguments defaults to config.properties)");
            System.out.println("java -jar dcMon.jar ATST.properties (will use alternate properties file)");
            System.exit(0);
		}

	    //logging variables
	    File file = new File(prop.getProperty("LogFile"));
	    FileWriter fw = new FileWriter(file, true);
	    PrintWriter pw = new PrintWriter(fw);
	    
		pw.println(new Date () + "\t" + "########################################");
		pw.println(new Date () + "\t" + "######## DCMon - Datacap Monitor #######");	
		pw.println(new Date () + "\t" + "########################################");
	

		//Logon and Connection Variables
		String serviceRoot = prop.getProperty("ServiceRoot");
		String userName = prop.getProperty("ServiceUser");
		String pwd = AESencrp.decrypt(prop.getProperty("ServicePassword"));
		int wsTimeout = Integer.parseInt(prop.getProperty("ServiceTimeout"));
		String[] appMonitorList = prop.getProperty("AppsToMonitor").split("\\,");
		int sessionIDLocation = Integer.parseInt(prop.getProperty("SessionIDLocation"));
		String pageSize = prop.getProperty("BatchListPageSize");
		
		
		//Print variables to log
		pw.println(new Date () + "\t" + "########################################");
		pw.println(new Date () + "\t" + "##############Variables#################");
		pw.println(new Date () + "\t" + "Service Root: " + serviceRoot);
		pw.println(new Date () + "\t" + "Service User: " + userName);
		pw.println(new Date () + "\t" + "Service Timeout: " + wsTimeout);
		pw.println(new Date () + "\t" + "App Monitor List: " + Arrays.toString(appMonitorList));
		pw.println(new Date () + "\t" + "Session Cookie ID Location: " + sessionIDLocation);
		pw.println(new Date () + "\t" + "Configuration File: " + configFile);
		pw.println(new Date () + "\t" + "########################################");
		
		
		
		//Service Definitions
		String serviceLogon = "Session/Logon";
		String serviceGetAppList = "Admin/GetApplicationList";
		String serviceGetBatchList = "Queue/GetBatchList";
		String serviceLogoff = "Session/Logoff";

		//Status variables
		String sessionID = null;			//SessionID that will be created upon initial logon and used as a cookie in subsequent transactions
		String activeAppList = null;
		Integer batchListCount = null;
		String logonRequest = null;
		String alertMessage = "";
		

		
		
		//***Use the below for decrypting credentials***
		
		/*
        	String passwordX = "xxx";
			String passwordEnc = AESencrp.encrypt(passwordX);
	        String passwordDec = AESencrp.decrypt(passwordEnc);
	        System.out.println("Plain Text : " + passwordX);
	        System.out.println("Encrypted Text : " + passwordEnc);
	        System.out.println("Decrypted Text : " + passwordDec);
	        System.exit(0);
		*/
		
		//Call service to get a list of all the active apps
		activeAppList = getActiveAppsList(serviceRoot + serviceGetAppList, wsTimeout, pw);
		// if appList = null -> error alert.
		if(activeAppList == null) {
			alertMessage += "ERROR: Could not get Datacap App List.\n";
		}
		
		
		//For each application...
		for (String appName : appMonitorList) {
			logonRequest	= "{\r\n" + 
					"	\"application\":\"" + appName + "\",\r\n" + 
					"	\"password\":\"" + pwd + "\",\r\n" + 
					"	\"station\":\"1\",\r\n" + 
					"	\"user\":\"" + userName + "\"\r\n" + 
					"}";
				
			sessionID = getLogonToken(logonRequest, serviceRoot + serviceLogon, sessionIDLocation, wsTimeout, pw, appName);

			if(sessionID == null) {
				alertMessage += "ERROR: Issues found with logon to " + appName + ".\n";
			}else {
				//Can only run further methods if we have a session ID			
				batchListCount = getBatchList(appName, serviceRoot + serviceGetBatchList, sessionID, pageSize, wsTimeout, pw);

				if(batchListCount == null) {
					alertMessage += "ERROR: Issues found getting batch list from " + appName + ".\n";
				}
				
				//do a session logoff
				logoffUser(serviceRoot + serviceLogoff, sessionID, appName, wsTimeout, pw);
			}
		}
		
		pw.println(new Date () + "\t" + "########################################");
		pw.println(new Date () + "\t" + "############ DCMon - Complete ##########");	
		pw.println(new Date () + "\t" + "########################################");
		if(alertMessage.length() > 0) {
			pw.println(new Date () + "\t" + "DCMon - Datacap Monitor Encountered Errors:");
			pw.println(new Date () + "\t" + alertMessage);
			pw.println(new Date () + "\t" + "Sending alert...");
			SendEmail alert = new SendEmail();
			alert.main(alertMessage, configFile);
		}else {
			pw.println(new Date () + "\t" + "DCMon - Datacap Monitor Completed Successfully (" + (System.currentTimeMillis() - lstartTime) + "ms.)");
		}
		pw.println(new Date () + "\t" + "########################################");	
		pw.close();
		pw = null;
		
	}
	
	static void logoffUser(String logoffWS, String sessionID, String appName, int wsTimeout, PrintWriter pw) {
		pw.println(new Date () + "\t" + "--> Logging off " + appName);	
	    //Timer variable for metrics
	    long lstartTime	= System.currentTimeMillis();
		try {
			URL dcWSURL = new URL(logoffWS);
			HttpURLConnection conn = (HttpURLConnection) dcWSURL.openConnection();
			conn.setDoOutput(true);
			conn.setConnectTimeout(wsTimeout);
			conn.setReadTimeout(wsTimeout);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setRequestProperty("Cookie", sessionID);
			OutputStream os = conn.getOutputStream();
			os.flush();
				
			if(conn.getResponseCode() == 200) {
				pw.println(new Date () + "\t" + "<-- Logoff of " + appName + ", " + sessionID.substring(0, 14) + " complete (" + (System.currentTimeMillis() - lstartTime) + "ms.) [OK]");
			}else {
				pw.println(new Date () + "\t" + "Exception logging off.  HTTP Error: " + conn.getResponseCode() + " [ERROR]");
			}
				
			  } catch (MalformedURLException e) {
				  e.printStackTrace();
			  } catch (IOException e) {
				  pw.println(new Date () + "\t" + "An I/O Exception Occurred [ERROR]");
			 }
		
		return;
	}
	
	static Integer getBatchList(String appName, String batchListWS, String sessionID, String pageSize, int wsTimeout, PrintWriter pw) {
		Integer batchListCount = null;
		pw.println(new Date () + "\t" + "--> Calling getBatchList module for " + appName);
		
		String wsOutputFull = "";
		String appNameEncoded = appName.replace(" ", "%20");
		//Encode app name as they sometime contain spaces

				
	    //Timer variable for metrics
	    long lstartTime	= System.currentTimeMillis();
		
		try {
			//200=page size, 0=1st page, qu_id sort by qu_id
			batchListWS += "/" + appNameEncoded + "/" + pageSize + "/0/qu_id";
			

			pw.println(new Date () + "\t" + "WSRequest: " + batchListWS);	
			URL dcWSURL = new URL(batchListWS);
			HttpURLConnection conn = (HttpURLConnection) dcWSURL.openConnection();
			conn.setDoOutput(true);
			conn.setConnectTimeout(wsTimeout);
			conn.setReadTimeout(wsTimeout);
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");	
			conn.setRequestProperty("Cookie", sessionID);

			if (conn.getResponseCode() != 200) {
				pw.println(new Date () + "\t" + "Exception calling " + batchListWS + ".  HTTP error code " + conn.getResponseCode() + ". [ERROR]");
				return batchListCount;
			}
			
			BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			
			String output;
			while((output = br.readLine()) != null){
				wsOutputFull += output;
			}
			
			JsonObject jsonObject = new JsonParser().parse(wsOutputFull).getAsJsonObject();
			pw.println(new Date () + "\t" + "Batch count for " + appName + " is " + jsonObject.get("Count") + " of " + pageSize);	
			//pw.println(new Date () + "\t" + "xxx" + jsonObject.get("qu_id"));
			batchListCount = Integer.decode(jsonObject.get("Count").toString());
			
			  } catch (Exception e) {
				  pw.println(new Date () + "\t" + "getBatchList module encountered issues. [ERROR]");
				  e.printStackTrace();
				  batchListCount = null;
				  return batchListCount;
			  }

			pw.println(new Date () + "\t" + "<-- getBatchList module for " + appName + " completed successfully (" + (System.currentTimeMillis() - lstartTime) + "ms.) [OK]");
		
		return batchListCount;
	}
	
	static String getActiveAppsList(String appListWS, int wsTimeout, PrintWriter pw) {
		String appList = "";
		pw.println(new Date () + "\t" + "--> Calling getAppsList module " + appListWS);	
	    //Timer variable for metrics
	    long lstartTime	= System.currentTimeMillis();

		try {
		URL dcWSURL = new URL(appListWS);
		HttpURLConnection conn = (HttpURLConnection) dcWSURL.openConnection();
		conn.setDoOutput(true);
		conn.setConnectTimeout(wsTimeout);
		conn.setReadTimeout(wsTimeout);
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "application/json");	

		if (conn.getResponseCode() != 200) {
			pw.println(new Date () + "\t" + "Exception calling " + appListWS + ".  HTTP error code " + conn.getResponseCode() + ". [ERROR]");
			appList = null;
			return appList;
		}
		
		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		
		String output;
		while((output = br.readLine()) != null){
			appList += output;
		}
		
		  } catch (Exception e) {
			  //e.printStackTrace();
				appList = null;
				pw.println(new Date () + "\t" + "getAppsList module encountered issues. [ERROR]");
				return appList;
		  }
		pw.println(new Date () + "\t" + "List of Apps:" + appList);		
		pw.println(new Date () + "\t" + "<-- getAppsList module completed successfully (" + (System.currentTimeMillis() - lstartTime) + "ms.) [OK]");
		return appList;
	}
	
	static String getLogonToken(String logonRequest, String logonURL, int sessionIDLocation, int wsTimeout, PrintWriter pw, String appName){
		pw.println(new Date () + "\t" + "--> Logging on to " + appName);
		String strLogonToken = null;
	    //Timer variable for metrics
	    long lstartTime	= System.currentTimeMillis();

		
		try {
		//pw.println(new Date () + "\t" + "Logging on to " + logonURL);	
		URL dcWSURL = new URL(logonURL);
		HttpURLConnection conn = (HttpURLConnection) dcWSURL.openConnection();
		conn.setDoOutput(true);
		conn.setConnectTimeout(wsTimeout);
		conn.setReadTimeout(wsTimeout);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");	

		
		OutputStream os = conn.getOutputStream();
		os.write(logonRequest.getBytes());
		os.flush();
			
		strLogonToken = conn.getHeaderField(sessionIDLocation);
		
		if(strLogonToken != null && conn.getResponseCode() == 200) {
			pw.println(new Date () + "\t" + "<-- Logon session token for " + appName + " obtained (" + (System.currentTimeMillis() - lstartTime) + "ms.)  " + "Token starts with \"" + strLogonToken.substring(0, 14) + "\" [OK]");
		}else {
			pw.println(new Date () + "\t" + "Could not obtain logon session token for " + appName + ". Please check Connection info and credentials.  HTTP Error: " + conn.getResponseCode() + "[ERROR]");
		}
			
		  } catch (MalformedURLException e) {
			  e.printStackTrace();
		  } catch (IOException e) {
			  //e.printStackTrace();
			  pw.println(new Date () + "\t" + "An I/O Exception Occurred");
			  pw.println(new Date () + "\t" + "ERROR: Could not obtain logon session token for " + appName + ". Please check Connection info and credentials.");
		 }
		
		return strLogonToken;
	}
		

}
