package com.jsql.util;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.jsql.i18n.I18n;
import com.jsql.model.InjectionModel;
import com.jsql.model.MediatorModel;

/**
 * Utility class used to connect to Github Rest webservices.
 * It uses jsql-robot profile to post data to Github.
 */
public class GitUtil {
	
    /**
     * Log4j logger sent to view.
     */
    private static final Logger LOGGER = Logger.getRootLogger();
    
    /**
     * Application useful informations as json object from Github repository.
     * Used to get current development version and community news.
     */
    private static JSONObject jsonObject;
    
    /**
     * Define explicit labels to declare method parameters.
     * Used for code readability only. 
     */
    public enum ShowOnConsole {
        YES,
        NO;
    }

    // Utility class
    private GitUtil() {
        // not called
    }

    /**
     * Verify if application is up to date against the version on Github.
     * @param displayUpdateMessage YES for manual update verification, hidden otherwise
     */
    public static void checkUpdate(ShowOnConsole displayUpdateMessage) {
        if (displayUpdateMessage == ShowOnConsole.YES) {
            LOGGER.trace(I18n.valueByKey("UPDATE_LOADING"));    
        }
        
        try {
            Float versionGit = Float.parseFloat(GitUtil.getJSONObject().getString("version"));
            if (versionGit > Float.parseFloat(InjectionModel.VERSION_JSQL)) {
                LOGGER.warn(I18n.valueByKey("UPDATE_NEW_VERSION"));
            } else if(displayUpdateMessage == ShowOnConsole.YES) {
                LOGGER.debug(I18n.valueByKey("UPDATE_UPTODATE"));
            }
        } catch (NumberFormatException | IOException e) {
            LOGGER.warn(I18n.valueByKey("UPDATE_EXCEPTION"), e);
        }
    }
    
    /**
     * Define the body of an issue to send to Github for an unhandled exception.
     * It adds different system data to the body and remove sensible data like
     * injection URL.
     * @param threadName name of thread where the exception occured
     * @param throwable unhandled exception to report to Gihub
     */
    public static void sendUnhandledException(String threadName, Throwable throwable) {
        String javaVersion = System.getProperty("java.version");
        String osArch = System.getProperty("os.arch");
        
        String clientDescription = 
              "```\n"
            + "jSQL: v"+ InjectionModel.VERSION_JSQL +"\n"
            + "Java: v"+ javaVersion +"-"+ osArch +"\n"
            + "OS: "+ System.getProperty("os.name") +" (v"+ System.getProperty("os.version") +")\n"
            + "Desktop: "+( System.getProperty("sun.desktop") != null ? System.getProperty("sun.desktop") : "undefined" )+"\n"
            + "Strategy: "+( MediatorModel.model().getStrategy() != null ? MediatorModel.model().getStrategy().instance().getName() : "undefined" )+"\n"
            + "Db engine: "+ MediatorModel.model().vendor.toString() +"\n"
            + "```\n"
            + "```\n"
            + "Exception on "+ threadName +"\n"
            + ExceptionUtils.getStackTrace(throwable).trim() +"\n"
            + "```";
        
        clientDescription = clientDescription.replaceAll("(https?://[.a-zA-Z_0-9]*)+", "");
          
        GitUtil.sendReport(clientDescription, ShowOnConsole.NO, "Unhandled "+ throwable.getClass().getSimpleName());
    }
    
    /**
     * Connect to Github webservices and create an Issue on the repository.
     * Used by translation protocol, unhandled exception detection and manual Issue reporting.
     * @param reportBody text of the Issue
     * @param showOnConsole in case of manual Issue reporting. Hidden in case of automatic reporting of unhandled exception.
     * @param reportTitle title of the Issue
     */
    public static void sendReport(String reportBody, ShowOnConsole showOnConsole, String reportTitle) {
    	// Check proxy
        if (!ProxyUtil.proxyIsResponding(showOnConsole)) {
            return;
        }

        // Connect to Github webservice
        HttpURLConnection connection = null;
        try {
        	// TODO define in properties
            URL githubUrl = new URL("https://api.github.com/repos/ron190/jsql-injection/issues");

            connection = (HttpURLConnection) githubUrl.openConnection();
            connection.setDefaultUseCaches(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Expires", "-1");
            connection.setRequestProperty("Content-Type", "application/json");
            
            // Authenticate as jsql-robot
            connection.setRequestProperty(
                "Authorization", 
                // TODO define in properties
                "token "+ StringUtils.newStringUtf8(Base64.decodeBase64("NGQ1YzdkYWE1NDQwYzdkNTk1YTZlODQzYzFlODlkZmMzNzQ1NDhlNg=="))
            );
            
            connection.setReadTimeout(ConnectionUtil.TIMEOUT);
            connection.setConnectTimeout(ConnectionUtil.TIMEOUT);
            connection.setDoOutput(true);

            // Set the content of the Issue
            DataOutputStream dataOut = new DataOutputStream(connection.getOutputStream());
            dataOut.writeBytes(
                new JSONObject()
                    .put("title", reportTitle)
                    .put("body", GitUtil.decimalHtmlEncode(reportBody))
                    .toString()
            );
            dataOut.flush();
            dataOut.close();
            
            // Read the response
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                String sourcePage = "";
                while ((line = reader.readLine()) != null) {
                    sourcePage += line;
                }

                if (showOnConsole == ShowOnConsole.YES) {
                    JSONObject jsonObjectResponse = new JSONObject(sourcePage);
                    String urlIssue = jsonObjectResponse.getString("html_url");
                    LOGGER.debug("Sent to Github: "+ urlIssue);
                }
            } catch (IOException e) {
                if (showOnConsole == ShowOnConsole.YES) {
                    LOGGER.warn("Read error: "+ e, e);
                }
            }
        } catch (IOException e) {
            if (showOnConsole == ShowOnConsole.YES) {
                LOGGER.warn("Error during Git report connection: "+ e, e);
            }
        }
    }
    
    /**
     * Displays news informations on the console from Github web service.
     * Infos concern the general roadmap for the application, current development status
     * and other useful statements for the community.
     */
    public static void showNews() {
        try {
            JSONArray news = GitUtil.getJSONObject().getJSONArray("news");
            for (int index = 0 ; index < news.length() ; index++) {
                LOGGER.info(news.get(index));
            }
        } catch (IOException e) {
            LOGGER.warn("Connection to the Github News Webservice failed", e);
        }
    }
    
    /**
     * Instanciate the jsonObject from json data if not already set.
     * @return jsonObject describing json data
     * @throws IOException if connection to json data fails
     */
    public static JSONObject getJSONObject() throws IOException {
        if (GitUtil.jsonObject == null) {
            String json = ConnectionUtil.getSource(
        		// TODO get from properties
                "https://raw.githubusercontent.com/ron190/jsql-injection/master/web/services/jsql-injection.json"
            );
            GitUtil.jsonObject = new JSONObject(json);
        }
        return GitUtil.jsonObject;
    }
    
    /**
     * Convert special characters like Chinese and Arabic letters to the corresponding html entities.
     * @param text string to encode
     * @return string encoded in html entities
     * TODO create specialized class
     */
    private static String decimalHtmlEncode(String text) {
        return GitUtil.encode(text, DECIMAL_HTML_ENCODER);
    }
    
    /**
     * Non trivial methods to convert unicode characters to html entities.
     * @param text string to encode
     * @param encoder schema of encoding
     * @return string representation using the encoder schema
     */
    private static String encode(String text, CharEncoder encoder) {
        StringBuilder buff = new StringBuilder();
        for ( int i = 0 ; i < text.length() ; i++)
            if (text.charAt(i) > 128) {
                encoder.encode(text.charAt(i), buff);
            } else {
                buff.append(text.charAt(i));
            }
        return ""+ buff;
    }
    
    /**
     * Define the schema of convertion to html entities.
     */
    private static final CharEncoder DECIMAL_HTML_ENCODER = new CharEncoder("&#", ";", 10); 
    
    /**
     * This utility class defines a schema used to encode a text into a specialized
     * representation 
     */
    private static class CharEncoder {
    	
        String prefix;
        String suffix;
        int radix;
        
        public CharEncoder(String prefix, String suffix, int radix) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.radix = radix;
        }
        
        void encode(char c, StringBuilder buff) {
            buff
            	.append(prefix)
            	.append(Integer.toString(c, radix))
            	.append(suffix);
        }
        
    }
    
}
