/**
 * Copyright (c) 2014 Stefan Feilmeier <stefan.feilmeier@fenecon.de>.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package de.fenecon.fems;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Properties;

import javax.net.ssl.HttpsURLConnection;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.io.ModbusSerialTransaction;
import net.wimpi.modbus.msg.ExceptionResponse;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.net.SerialConnection;
import net.wimpi.modbus.util.SerialParameters;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONObject;

import de.fenecon.fems.exceptions.FEMSException;
import de.fenecon.fems.exceptions.NoInternetException;
import de.fenecon.fems.exceptions.NoIPException;
import de.fenecon.fems.tools.FEMSIO;
import de.fenecon.fems.tools.FEMSIO.UserLED;
import de.fenecon.fems.tools.FEMSYaler;

public class FEMSCore {
	private final static SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
	private final static int modbusUnitID = 4;
	private final static int dessModbusSOCAddress = 10143;
	private final static String modbusInterface = "/dev/ttyUSB0";
	
	public static void main(String[] args) {
		Options options = new Options();
		options.addOption("h", "help", false, "");
		options.addOption(null, "init", false, "Initialize system");
		
		CommandLineParser parser = new GnuParser();
		CommandLine cmd;
		try {
			cmd = parser.parse(options, args);
			
			if(cmd.hasOption("init")) {
				init();
		    } else {
		    	help(options);
			}
		} catch (ParseException e) {
			help(options);
		}
	}
	
	private static String logText = null;
	private static void logInfo(String text) {
		System.out.println(text);
		if(logText == null) {
			FEMSCore.logText = text;
		} else {
			FEMSCore.logText += "\n" + text;
		}
	}
	private static void logError(String text) {
		System.out.println("ERROR: " + text);
		if(logText == null) {
			FEMSCore.logText = "ERROR: " + text;
		} else {
			FEMSCore.logText += "\nERROR: " + text;
		}
	}

	/**
	 * Show all commandline options
	 * @param options
	 */
	private static void help(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp( "FemsTester", options );		
	}

	/**
	 * Checks if DPKG (package manager for Debian) is running, e.g. during an 
	 * "aptitude full-upgrade" session
	 * @return
	 */
	private static boolean isDpkgRunning() {
		if(Files.exists(Paths.get("/var/lib/dpkg/lock"))) {
			Runtime rt = Runtime.getRuntime();
			Process proc;
			InputStream in = null;
			int lsof = -1;
			try {
				int c;
				proc = rt.exec("/usr/bin/lsof /var/lib/dpkg/lock");
				in = proc.getInputStream();
				while ((c = in.read()) != -1) { lsof = c; }
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if(in != null) { try { in.close(); } catch (IOException e) { e.printStackTrace(); } }
			}
			if(lsof != -1) {
				return true;
			}
		};	
		return false;
	}
	
	/**
	 * Turns all FEMS outputs off
	 */
	private static void turnAllOutputsOff(FEMSIO femsIO) {
		// turn all user leds off
		try { femsIO.switchUserLED(UserLED.LED1, false); } catch (IOException e) { logError(e.getMessage()); }
		try { femsIO.switchUserLED(UserLED.LED2, false); } catch (IOException e) { logError(e.getMessage()); }
		try { femsIO.switchUserLED(UserLED.LED3, false); } catch (IOException e) { logError(e.getMessage()); }
		try { femsIO.switchUserLED(UserLED.LED4, false); } catch (IOException e) { logError(e.getMessage()); }
		
		// turn all relay outputs off
		femsIO.RelayOutput_1.low();
		femsIO.RelayOutput_2.low();
		femsIO.RelayOutput_3.low();
		femsIO.RelayOutput_4.low();
		
		// turn all analog outputs off and set divider to voltage
		FEMSIO.setAnalogOutput(femsIO.AnalogOutput_1, 0);
		FEMSIO.setAnalogOutput(femsIO.AnalogOutput_2, 0);
		FEMSIO.setAnalogOutput(femsIO.AnalogOutput_3, 0);
		FEMSIO.setAnalogOutput(femsIO.AnalogOutput_4, 0);
		femsIO.AnalogOutput_1_divider.high();
		femsIO.AnalogOutput_2_divider.high();
		femsIO.AnalogOutput_3_divider.high();
		femsIO.AnalogOutput_4_divider.high();
	}
	
	/**
	 * Checks if the current system date is valid
	 * @return
	 */
	private static boolean isDateValid() {
		Calendar now = Calendar.getInstance();
		int year = now.get(Calendar.YEAR);  
		if(year < 2014 || year > 2025) {
			return false;
		} else {
			return true;
		}
	}
	
	/**
	 * Checks if modbus connection to storage system is working
	 * @return
	 */
	private static boolean isModbusWorking() {
		SerialParameters params = new SerialParameters();
		params.setPortName(modbusInterface);
		params.setBaudRate(9600);
		params.setDatabits(8);
		params.setParity("None");
		params.setStopbits(1);
		params.setEncoding(Modbus.SERIAL_ENCODING_RTU);
		params.setEcho(false);
		params.setReceiveTimeout(500);
		SerialConnection serialConnection = new SerialConnection(params);
		try {
			serialConnection.open();
		} catch (Exception e) {
			logError("Modbus connection error");
			return false;
		}
		ModbusSerialTransaction modbusSerialTransaction = null;
		ReadMultipleRegistersRequest req = new ReadMultipleRegistersRequest(dessModbusSOCAddress, 1);
		req.setUnitID(modbusUnitID);
		req.setHeadless();	
		modbusSerialTransaction = new ModbusSerialTransaction(serialConnection);
		modbusSerialTransaction.setRequest(req);
		modbusSerialTransaction.setRetries(1);
		try {
			modbusSerialTransaction.execute();
		} catch (ModbusException e) {
			logError("Modbus execution error");
			return false;
		}
		ModbusResponse res = modbusSerialTransaction.getResponse();
		
		if (res instanceof ReadMultipleRegistersResponse) {
			logInfo("Modbus result: " + (ReadMultipleRegistersResponse)res);
			return true;
    	} else if (res instanceof ExceptionResponse) {
    		logError("Modbus error: " + ((ExceptionResponse)res).getExceptionCode());
    	} else {
    		logError("Modbus undefined response");
    	}
		return false;
	}
	
	/**
	 * Gets an IPv4 network address
	 * @return
	 */
	private static InetAddress getIPaddress() {
    	try {
			NetworkInterface n = NetworkInterface.getByName("eth0");
			Enumeration<InetAddress> ee = n.getInetAddresses();
			while (ee.hasMoreElements()) {
				InetAddress i = (InetAddress) ee.nextElement();
				if(i instanceof Inet4Address) {
					return i;
		        }
		    }
    	} catch (SocketException e) { /* no IP-Address */ }
    	return null; 
	}
	
	/**
	 * Send message to Online-Monitoring
	 */
	private static JSONObject sendMessage(String urlString, String apikey, String message) {
		// create JSON		
		JSONObject mainJson = new JSONObject();
		mainJson.put("version", 1);
		mainJson.put("apikey", apikey);
		mainJson.put("timestamp", new Date().getTime());
		mainJson.put("content", "system");
		mainJson.put("system", message);
		mainJson.put("ipv4", getIPaddress()); // local ipv4 address
		// send to server
		HttpsURLConnection con;
		try {
			URL url = new URL(urlString);
			con = (HttpsURLConnection)url.openConnection();
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type","application/json"); 
			con.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0;Windows98;DigExt)"); 
			con.setDoOutput(true); 
			con.setDoInput(true);
			DataOutputStream output = new DataOutputStream(con.getOutputStream());
			try {
				output.writeBytes(mainJson.toString());
			} finally {
				output.close();
			}
			// evaluate response
			if(con.getResponseCode() == 200) {
				logInfo("Successfully sent system-data; server answered: " + con.getResponseMessage());
			} else {
				logError("Error while sending system-data; server response: " + con.getResponseCode() + "; will try again later");
			}
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			JSONObject retJson = null;
	        try {
	            String inputLine = in.readLine();
	            if(inputLine != null) {
		        	try {
		        		retJson = new JSONObject(inputLine);
		        	} catch (Exception e) {}
		        }
	        } finally {
	        	in.close();
	        }
	        return retJson;
		} catch (IOException e) {
			logError(e.getMessage());
		}
		return null;
	}
	
	/**
	 * Handle json returned from Online-Monitoring
	 * @param json
	 * @throws Exception 
	 */
	private static void handleReturnJson(JSONObject json) throws Exception {
    	if(json != null && json.has("yaler")) {
    		logInfo("Activate Yaler tunnel");
    		String relayDomain = json.getString("yaler");
    		FEMSYaler.getFEMSYaler().activateTunnel(relayDomain);
    	} else {
    		logInfo("Deactivate Yaler tunnel");
    		FEMSYaler.getFEMSYaler().deactivateTunnel();
    	}
	}
	
	/**
	 * Initialize FEMS/FEMSmonitor system
	 */
	private static void init() {
		logInfo("Start FEMS Initialization");
		boolean returnWithError = false;
		FEMSIO femsIO = FEMSIO.getFEMSIO();
		FEMSLcdAgent lcdAgent = FEMSLcdAgent.getFEMSLcdAgent();
		lcdAgent.start();
		Runtime rt = Runtime.getRuntime();
		Process proc;
	
		// init LCD display
		lcdAgent.setFirstRow("FEMS Selbsttest");
				 
		// check if dpkg is running during startup of initialization
		boolean dpkgIsRunning = isDpkgRunning();
		if(dpkgIsRunning) {
			logInfo("DPKG is running -> no system update");
		} else {
			logInfo("DPKG is not running -> will start system update");
		}
		
		// read FEMS properties from /etc/fems
		Properties properties = new Properties();
		BufferedInputStream stream = null;
		try {
			stream = new BufferedInputStream(new FileInputStream("/etc/fems"));
			properties.load(stream);
			if(stream != null) stream.close();
		} catch (IOException e) {
			logError(e.getMessage());
		}
		String apikey = properties.getProperty("apikey");

		// turn outputs off
		logInfo("Turn outputs off");
		turnAllOutputsOff(femsIO);
		
		try {
			// check for valid ip address
			InetAddress ip = getIPaddress();
			if(ip == null) {
		        try {
					proc = rt.exec("/sbin/dhclient eth0");
					proc.waitFor();
					ip = getIPaddress(); /* try again */
					if(ip == null) { /* still no IP */
						throw new NoIPException();
					}
				} catch (IOException | InterruptedException e) {
					throw new NoIPException(e.getMessage());
				}
			}
			logInfo("IP: " + ip.getHostAddress());
			lcdAgent.status.setIp(true);
			lcdAgent.offer("IP ok");
			try { femsIO.switchUserLED(UserLED.LED1, true); } catch (IOException e) { logError(e.getMessage()); }		
	
			// check time
			if(isDateValid()) { /* date is valid, so we check internet access only */
				logInfo("Date was ok: " + dateFormat.format(new Date()));
				try {
					URL url = new URL("https://fenecon.de");
					URLConnection con = url.openConnection();
					con.setConnectTimeout(1000);
					con.getContent();
				} catch (IOException e) {
					throw new NoInternetException(e.getMessage());
				}	
			} else {
				logInfo("Date was not ok: " + dateFormat.format(new Date()));
				try {
					proc = rt.exec("/usr/sbin/ntpdate -b -u fenecon.de 0.pool.ntp.org 1.pool.ntp.org 2.pool.ntp.org 3.pool.ntp.org");
					proc.waitFor();
					if(!isDateValid()) {
						throw new NoInternetException("Date is still wrong: " + dateFormat.format(new Date()));
					}
					logInfo("Date is now ok: " + dateFormat.format(new Date()));
				} catch (IOException | InterruptedException e) {
					throw new NoInternetException(e.getMessage());
				}
			}
			logInfo("Internet access is available");
			lcdAgent.status.setInternet(true);
			lcdAgent.offer("Internet ok");
			try { femsIO.switchUserLED(UserLED.LED2, true); } catch (IOException e) { logError(e.getMessage()); }	
					
			// test modbus
			if(isModbusWorking()) {
				logInfo("Modbus is ok");
				lcdAgent.status.setModbus(true);
				lcdAgent.offer("RS485 ok");
			} else {
				logError("Modbus is not ok");
				lcdAgent.offer("RS485-Fehler");
			}
		
			// announce systemd finished
			logInfo("Announce systemd: ready");
			try {
				proc = rt.exec("/bin/systemd-notify --ready");
				proc.waitFor();
			} catch (IOException | InterruptedException e) {
				logError(e.getMessage());
			}
		} catch (FEMSException e) {
			logError(e.getMessage());
			lcdAgent.offer(e.getMessage());
			returnWithError = true;
		}
		lcdAgent.stopAgent();
		try { lcdAgent.join(); } catch (InterruptedException e) { ; }
		
		// Send message
		if(apikey == null) {
			logError("Apikey is not available");
		} else {		
			JSONObject returnJson = sendMessage("https://fenecon.de/femsmonitor", apikey, logText);
			try {
				// start yaler if necessary
				handleReturnJson(returnJson);
			} catch (Exception e) {
				logError(e.getMessage());
			}
		}
		
		if(lcdAgent.status.getInternet() && !dpkgIsRunning) {
			// start update
			logInfo("Start system update");
			try {
				proc = rt.exec("/etc/cron.daily/fems-autoupdate");
				proc.waitFor();
			} catch (IOException | InterruptedException e) {
				logError(e.getMessage());
			}
		} else {
			logInfo("Do not start system update");
		}
		
		// Exit
		if(returnWithError) {
			logError("Finished with error");
			System.exit(1);
		} else {
			logInfo("Finished without error");
			System.exit(0);
		}
	}
}