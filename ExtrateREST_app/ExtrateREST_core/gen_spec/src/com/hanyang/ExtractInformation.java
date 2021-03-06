package com.hanyang;

import java.io.File;

import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.tika.Tika;
import org.json.JSONException;
import org.json.JSONObject;

import gate.Annotation;
import gate.AnnotationSet;
import gate.Document;
import gate.DocumentContent;
import gate.Factory;
import gate.FeatureMap;
import gate.Gate;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import gate.util.Out;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class ExtractInformation {

	/** The Corpus Pipeline application to contain ANNIE */
	// corpus/www.instagram.com dev.twitter.com www.twilio.com www.youtube.com
	// www.flickr.com
	private static String API_NAME = "google";
	private static String API_FOLDER = "developers.google.com";
	private static String FilteredSet_PATH = "./FilteredSet/" + API_FOLDER;
	private static String CompareSet_PATH = "./CompareSet/" + API_NAME;
	
	public static void main(String[] args) throws GateException, JSONException, IOException {
		// System.out.close();
		
		if (args.length > 0) {
			
			API_FOLDER = args[0].split("//")[1].split("/")[0];
			API_NAME = args[0].split("//")[1].split("\\.")[1];
			
			
			// google have several APIs
			if (API_NAME.contains("google")) {
				API_FOLDER = args[0].split("//")[1].split("/")[1];
				API_NAME = args[0].split("//")[1].split("/")[1];
			} else if (API_NAME.contains("/")) {
				if (!args[0].split("//")[1].startsWith("www") && !args[0].split("//")[1].startsWith("api") ) {
					API_NAME = args[0].split("//")[1].split("\\.")[0];
				}
					
			}
			FilteredSet_PATH = "./FilteredSet/" + API_FOLDER;
			CompareSet_PATH = "./CompareSet/" + API_NAME;
		}

		
		// 0. get properties
		Settings.getPropertiesReader(CompareSet_PATH + "/"+ API_NAME);
		
		// init gate
		Gate.init();

		Out.prln(System.getProperty("-------User.dir--------"));
		Out.prln(System.getProperty("user.dir"));
		Out.prln(System.getProperty("-------FilterSet--------"));
		Out.prln(FilteredSet_PATH);
		
		// 1. create corpus
		File folder = new File(FilteredSet_PATH);
		File[] listFiles = folder.listFiles();
		new File(CompareSet_PATH).mkdirs();
		File compareSet = new File(CompareSet_PATH);

		// 2. generate openAPI according to pattern
		generateOpenAPI(listFiles);
		
		// 3. compare the json files and select the final one.
		selectOpenAPI(compareSet);

		// 4. write properties
//		Settings.writeProperties(CompareSet_PATH + "/"+ API_NAME);
		
	}

	public static void generateOpenAPI(File[] listFiles) throws ResourceInstantiationException, JSONException, IOException, MalformedURLException {
		// 2.1 initial the specification
		GenerateMain mainObject = new GenerateMain();
		JSONObject openAPI = mainObject.generateStructure();
		ProcessBaseUrl processBa = new ProcessBaseUrl();
		String baseUrl = null;
		// 2.2. different mode
		// if it's search mode, find the common base url first
		if (Settings.SEARCHBASE == true) {
			baseUrl = processBa.searchBaseUrl(listFiles, API_NAME);
			baseUrl = processBa.cleanBaseUrl(baseUrl);
			Out.prln(baseUrl);
		}
		
		// 2.3. check each html file
		for (int i = 0; i < listFiles.length; i++) {
			// print the file name
			Out.prln("=============File name=======================");
			Out.prln(listFiles[i].getPath());
			String type = new Tika().detect(listFiles[i].getPath());
			// only detect html
			if (type.equals("text/html")) {
				executeFile(listFiles[i].getPath(), openAPI, baseUrl);
			}
		}

		// 2.4. prune openAPI
		openAPI = processBa.handleBaseUrl(openAPI, baseUrl);

		// 2.5. write to file
		writeOpenAPI(openAPI);

	}

	public static void executeFile(String path, JSONObject openAPI, String baseUrl)
			throws ResourceInstantiationException, JSONException, IOException {
		URL u = Paths.get(path).toUri().toURL();
		FeatureMap params = Factory.newFeatureMap();
		params.put("sourceUrl", u);
		Document doc = (Document) Factory.createResource("gate.corpora.DocumentImpl", params);

		// 2.3.1 get all text
		DocumentContent textAll = doc.getContent();

		// 2.3.2 initial openAPI
		ProcessMethod processMe = new ProcessMethod();
		processMe.generateDefault(openAPI);

		if (Settings.MODE == "null") {
			tagMode(openAPI, doc, textAll, processMe);
		} else {
			regexMode(openAPI, doc, textAll, processMe);
		}

	}

	private static void tagMode(JSONObject openAPI, Document doc,
			DocumentContent textAll, ProcessMethod processMe) throws JSONException {
		String strAll = textAll.toString();

		List<JSONObject> infoJson = new ArrayList<JSONObject>();
		// 1 get original markups
		doc.setMarkupAware(true);

		AnnotationSet annoOrigin = doc.getAnnotations("Original markups");
		AnnotationSet annoH1 = annoOrigin.get("h1");
		AnnotationSet annoCode = annoOrigin.get("code");

		// 2.1 generate info json based on the h1 tag
		genInfoJsonTag(doc, processMe, strAll, infoJson, annoH1);
		// 2.2 generate info json based on the code tag
		genInfoJsonTag(doc, processMe, strAll, infoJson, annoCode);
		

		Out.prln("---------INFO JSON-------");
		Out.prln(infoJson.toString());

		handleREQRESPARA(openAPI, doc, processMe, strAll, infoJson, annoOrigin);
	}

	private static void genInfoJsonTag(Document doc, ProcessMethod processMe, String strAll,
			List<JSONObject> infoJson, AnnotationSet annoTag) throws JSONException {
		String actionStr = null;
		String urlString = null;
		Iterator<Annotation> urlIter = annoTag.iterator();
		while (urlIter.hasNext()) {
			Annotation anno = (Annotation) urlIter.next();
			String urlText = gate.Utils.stringFor(doc, anno);
			JSONObject sectionJson = new JSONObject();

			if (processMe.isUrlPath(urlText, anno, strAll, API_NAME)) {
				if (anno.getType().equals("h1")) {

					urlString = "?method=" + urlText;
					if (API_NAME.equals("aws")) {
						urlString = "?Operation=" + urlText;
					}
					actionStr = processMe.findAction(urlString);
				} else if (anno.getType().equals("code")) {
					urlString = urlText.split(" ")[1];
					actionStr = urlText.split(" ")[0];
				}

				Out.prln("==========Url Action=================");
				Out.prln(urlString + "  " + actionStr);

				if (urlString != null && actionStr != null) {
					int location = anno.getStartNode().getOffset().intValue();
					// set url/action in the json
					JSONObject acJson = new JSONObject();
					acJson.put(actionStr.toUpperCase(), location);
					sectionJson.put("action", acJson);
					JSONObject urJson = new JSONObject();
					urJson.put(urlString, location);
					sectionJson.put("url", urJson);
				}
			}

			if (sectionJson.length() > 0) {
				infoJson.add(sectionJson);
			}
		}
	}

	private static void regexMode(JSONObject openAPI,  Document doc, DocumentContent textAll, ProcessMethod processMe) throws JSONException {
		// 4.1 search for the GET https
		String strAll = textAll.toString();
		List<JSONObject> infoJson = new ArrayList<JSONObject>();
		// 1 get original markups
		doc.setMarkupAware(true);

		AnnotationSet annoOrigin = doc.getAnnotations("Original markups");

		// 2. generate info json
		getInfoJsonRegex(processMe, strAll, infoJson);

		Out.prln("---------INFO JSON-------");
		Out.prln(infoJson.toString());

		handleREQRESPARA(openAPI, doc, processMe, strAll, infoJson, annoOrigin);
	}

	public static void handleREQRESPARA(JSONObject openAPI, Document doc, ProcessMethod processMe, String strAll,
			List<JSONObject> infoJson, AnnotationSet annoOrigin) throws JSONException {
		// 3. handle method url
		if (!infoJson.isEmpty()) {
			// add all the url/action pair
			processMe.addAllParaURL(openAPI, strAll, infoJson);
		}	
		
		// 4. handle request template/code
		ProcessRequest processReq = new ProcessRequest();
		processReq.handleRequestTemplate(openAPI, doc, processMe, strAll, infoJson, annoOrigin);
				
		// 5. handle response template
		ProcessResponse processRe = new ProcessResponse();
		processRe.handleResponseTemplate(openAPI, doc, processMe, strAll, infoJson, annoOrigin);
			
		// 6. handle parameter
		ProcessParameter processPa = new ProcessParameter();
		processPa.handleParaTemplate(openAPI, doc, processMe, strAll, infoJson, annoOrigin);
	}

	private static void getInfoJsonRegex(ProcessMethod processMe, String strAll, List<JSONObject> infoJson) throws JSONException {
		String regexAll = null;
		if (!Settings.EXISTVERB.equals("no")) {
			if (Settings.REVERSE.equals("no")) {
				regexAll = "(?si)((get)|(post)|(" + Settings.ABBREV_DELETE + ")|(put)|(patch)){1}"+ Settings.STUFFING + Settings.MODE;
			} else {
				regexAll = "(?si)" +  Settings.MODE + Settings.STUFFING +"((get)|(post)|(" + Settings.ABBREV_DELETE + ")|(put)|(patch)){1}";
			}
		} else {
			if (Settings.MODE.equals("key")) {
				regexAll = "(?si)"+ Settings.URLKEY + Settings.STUFFING;
			}
			
		}
		
		Pattern p = Pattern.compile(regexAll);
		Matcher matcher = p.matcher(strAll);
		String actionStr = null, urlString = null;
		int uLocation = 0;

		while (matcher.find()) {
			JSONObject sectionJson = new JSONObject();
			int startIndex = matcher.start();
			Out.prln("start: " + matcher.start());
			Out.prln("end:   " + matcher.end());
			String matchStr;
			try {
				if (Settings.REVERSE.equals("no")) {
					
					// no reverse: get + url
					if (Settings.MODE.startsWith("http")) {
						// Fix 2: suppose the URL length < 160
						matchStr = strAll.substring(matcher.start(), matcher.end() + 160);
					} else if (Settings.MODE.equals("/")){
						// partial URL length < 100
						matchStr = strAll.substring(matcher.start(), matcher.end() + 100);
					} else if (Settings.MODE.equals("key")){
						// key url
						matchStr = strAll.substring(matcher.start(), matcher.end() + 160);
					} else {
						matchStr = strAll.substring(matcher.start(), matcher.end() + 160);
					}
					
				} else {
					// reverse mode: url + get
					matchStr = strAll.substring(matcher.start(), matcher.end());
				}

			} catch (Exception e) {
				matchStr = strAll.substring(matcher.start(), matcher.end());
			}
			Out.prln("========matchSTR==============");
			Out.prln(matchStr);

			if (Settings.MODE.equals("key")) {
				if (matchStr.contains("http")) {
					urlString = matchStr.substring(matchStr.indexOf("http")).split("\n")[0];
				} else if (matchStr.contains("/")) {
					matchStr = matchStr.split("\n")[0].trim();
					int begin = matchStr.lastIndexOf(" ");
					int end = matchStr.indexOf("/");
					if (begin<end){
						urlString = matchStr.substring(begin);
					} else {
						//ignore the url
						continue;
					}
				} else {
					continue;
				}
				
				// handle url, make it short and clean
				uLocation = matcher.start();
				sectionJson = writeUrl(processMe, urlString, sectionJson, uLocation);
			} else {
				
				// match reversed action
				String actionRegex = "((teg)|(tsop)|(" + new StringBuilder(Settings.ABBREV_DELETE).reverse().toString()
						+ ")|(tup)|(hctap))";
				Pattern action = Pattern.compile(actionRegex, Pattern.CASE_INSENSITIVE);
				// match the reversed string, from right to left
				Matcher matcherAction = action.matcher(new StringBuilder(matchStr).reverse());
				// find the first match
				if (matcherAction.find()) {
					// find the action which is nearest to http
					Out.prln("matchStart： " + matcherAction.start());
					// Out.prln("==========real Action============");
					int acOffset = matchStr.length() - matcherAction.start() - Settings.MODE.length();
					int acLocation = startIndex + acOffset;
					// Out.prln(strAll.substring(acLocation, matcher.end()));
					Out.prln("==========REST Action============");
					
					actionStr = new StringBuilder(matcherAction.group(1)).reverse().toString();
					
					JSONObject acJson = new JSONObject();
					acJson.put(actionStr.toUpperCase(), acLocation);
					sectionJson.put("action", acJson);
					
					Out.prln(actionStr);
				}
				// match endpoint
				String regexHttp = null;
				if (Settings.REVERSE.equals("no")) {
					
					if (!Settings.MODE.equals("key")) {
						regexHttp = Settings.MODE;
					}
				} else {
					regexHttp = new StringBuilder(Settings.MODE).reverse().toString();
				}
				
				Pattern endpoint = Pattern.compile(regexHttp, Pattern.CASE_INSENSITIVE);
				
				Matcher endpointMatcher;
				
				if (Settings.REVERSE.equals("no")) {
					endpointMatcher = endpoint.matcher(matchStr);
					if (endpointMatcher.find()) {
						Out.prln("urlStart： " + endpointMatcher.start());
						uLocation = startIndex + endpointMatcher.start();
						
						// handle url
						urlString = matchStr.substring(endpointMatcher.start()).split("\n")[0].trim();
						Out.prln("raw url: " + urlString);
						
						// in some cases, you need to remove whitespace  /v2/projects/ project_id => /v2/projects/project_id
						if (Settings.URLMIDDLE.length()!= 0) {
							// just remove one whitespace is enough?
							if (!Settings.URLAFTER.equals(" ")) {
								Matcher urlMatcher = Pattern.compile(Settings.PARAKEY, Pattern.CASE_INSENSITIVE).matcher(urlString);
								if (urlMatcher.find()) {
									// remove the parameter key 
									urlString = urlString.substring(0, urlMatcher.start());
								}
								urlString = urlString.replaceAll(Settings.URLMIDDLE, "");
							} else {
								urlString = urlString.replaceFirst(Settings.URLMIDDLE, "");
							}
							
						}
						
						//	/groups Parameters => /groups
						if (Settings.URLAFTER.length()!= 0) {
							urlString = urlString.split(Settings.URLAFTER)[0];
						}
						
						Out.prln("refine url: " + urlString);
						
						// handle url, make it short and clean
						sectionJson = writeUrl(processMe, urlString, sectionJson, uLocation);
					}
				} else {
					endpointMatcher = endpoint.matcher(new StringBuilder(matchStr).reverse().toString());
					// find the first match
					if (endpointMatcher.find()) {
						// find the action which is nearest to http
						Out.prln("matchStart： " + endpointMatcher.start());
						Out.prln("matchEnd： " + endpointMatcher.end());
						// Out.prln("==========real Action============");
						int urOffset = matchStr.length() - endpointMatcher.start() - Settings.MODE.length();
						uLocation = startIndex + urOffset;
						// Out.prln(strAll.substring(urLocation,
						// endpointMatcher.end()));
						// .split("\n")[0].trim()
						Out.prln("matchLength: " + matchStr.length());
						// if offset < 0, next run
						if (urOffset < 0)
							continue;
						urlString = matchStr.substring(urOffset).split("\n")[0].trim();
						
						// writeUrl
						sectionJson = writeUrl(processMe, urlString, sectionJson, uLocation);
					}
				}
			}
			

			
			
			// if it's the verb doesn't exist
			if (Settings.EXISTVERB.equals("no")) {
				sectionJson.put("action", new JSONObject().put(processMe.findAction(urlString), uLocation));
			} 
			
			if (API_NAME.contains("ebay")) {
				if (processMe.isSanboxUrl(urlString)) {
					continue;
				}
			}
			// Write into openAPI
			// After matching table, we write url/action into openAPI
			infoJson.add(sectionJson);
		}
	}



	
	
	
	private static JSONObject writeUrl(ProcessMethod processMe, String urlString, JSONObject sectionJson, int uLocation)
			throws JSONException {
		urlString = processMe.cleanUrl(urlString);
		urlString = processMe.compressUrl(urlString);
		Out.prln("==========URL ADDRESS============");
		Out.prln(urlString);

		JSONObject urJson = new JSONObject();
		urJson.put(urlString, uLocation);
		sectionJson.put("url", urJson);

		return sectionJson;
	}


	public static void writeOpenAPI(JSONObject openAPI) throws IOException {

		// Print pretty openAPI
		String prefix = null;
		if (Settings.MODE.startsWith("http")) {
			prefix = Settings.MODE.split(":")[0];
		}
		String fileName = prefix + "_" + Settings.TEMPLATE + "_" + Settings.NUMBER + "_" + Settings.ABBREV_DELETE + "_" + Settings.REVERSE + ".json";
		writeFile(openAPI.toString(), fileName);
	}

	public static void writeFile(String openAPI, String fileName) throws IOException {
		FileWriter fileWriter = new FileWriter(CompareSet_PATH + "/" + fileName);
		String swString = openAPI.toString();
		JsonParser parser = new JsonParser();
		JsonElement jelement = parser.parse(swString);

		Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
		String prettyJson = gson.toJson(jelement);

		fileWriter.write(prettyJson);
		fileWriter.close();
	}

	public static void selectOpenAPI(File folder) throws IOException {
		File[] listNew = folder.listFiles();
		String finalName = "OpenAPI.json";
		File finalFile = new File(CompareSet_PATH + "/" + finalName);

		for (int i = 0; i < listNew.length; i++) {
			String type = new Tika().detect(listNew[i].getPath());
			// only detect .json
			if (type.equals("application/json")) {
				// ignore final file
				if (listNew[i].getName() != finalName) {
					// select the maximum size file
					if (listNew[i].length() > finalFile.length()) {
						writeFile(new String(Files.readAllBytes(listNew[i].toPath())), finalName);
					}
				}
			}
		}
	}

}
