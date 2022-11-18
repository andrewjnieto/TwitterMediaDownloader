import org.json.JSONArray;
import org.json.JSONObject;
import org.tinylog.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class UranusExporter {
    private static HttpClient httpClient;
    private String bearerHeaderValue;

    public UranusExporter(String bearerToken) {
        httpClient = HttpClient.newHttpClient();
        this.bearerHeaderValue = "Bearer " + bearerToken;
        if (bearerToken == null || bearerToken.isEmpty() || bearerToken.matches("\\s+") || !testBearerToken()) {
            Logger.error(new IllegalArgumentException("The BEARER TOKEN is not valid: " + bearerToken));
        }
        Logger.info("Exporter created with with BEARER TOKEN {}", bearerToken);
    }

    private boolean testBearerToken() {
        String requestURL = "https://api.twitter.com/2/users/by/username/Twitter";
        HttpResponse<String> twitterAPIResponse = this.executeTwitterAPIRequest(requestURL);
        JSONObject responseObj = new JSONObject(twitterAPIResponse.body());
        return responseObj.has("data");
    }

    //Consider adding url validation here using the apache commons validator

    /**
     * Exceute a twitter request to a particular resource url.
     *
     * @param resourceURL The intended resource to retrieve
     * @return HTTPResponse from the server.
     */
    public HttpResponse<String> executeTwitterAPIRequest(String resourceURL) {
        var request = HttpRequest.newBuilder(URI.create(resourceURL))
                .GET()
                .headers("Authorization", this.bearerHeaderValue)
                .build();
        //Logger.trace("Building new api request to RESOURCE {}", resourceURL);
        HttpResponse<String> httpResponse = null;
        try {
            httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interruptExcept) {
            Logger.error("The request thread was interrupted");
        } catch (IOException ioExcept) {
            Logger.error("The request experienced a general io exception");
        }
        return httpResponse;
    }


    private String getNextPaginationToken(JSONObject likedTweetsResponse) {
        JSONObject metadata = likedTweetsResponse.getJSONObject("meta");
        if (!metadata.has("next_token")) {
            return null;
        }
        return metadata.getString("next_token");
    }

    public void pauseExecution(String epochTime) throws Exception {
        DateFormat dateFormat = new SimpleDateFormat("hh:mm:ss a");
        Calendar cal = Calendar.getInstance();
        Date epochDate = new Date(Long.parseLong(epochTime) * 1000);
        Date currentTime = new Date();
        Logger.info("THE CURRENT TIME IS {}", dateFormat.format(cal.getTime()));
        long timeToWait = (epochDate.getTime() - currentTime.getTime()) + 5000;
        if (timeToWait > Integer.MAX_VALUE) {
            throw new Exception("The number of milliseconds to wait exceeds that of the allowed range.");
        }
        cal.add(Calendar.MILLISECOND, (int) timeToWait);
        Logger.info("EXECUTION PAUSED UNTIL ~{} due to rate limit.", dateFormat.format(cal.getTime()));
        if (timeToWait > 0) {
            Thread.sleep(timeToWait);
        }
    }

    private void executeProcess(String dirPath, String cmdArgs) {
        String[] processArgs = new String[]{"bash", "-c", cmdArgs};
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(processArgs, null, new File(dirPath));
            process.waitFor();
        } catch (InterruptedException interruptExcept) {
            Logger.error("The request thread was interrupted");
        } catch (IOException ioExcept) {
            Logger.error("The request experienced a general io exception");
        }
    }

    public List<List<String>> retrieveLikedTweets(String username) {
        String baseRetrievalURL = "https://api.twitter.com/2/users/" + username + "/liked_tweets";
        String paginationToken = "";
        List<List<String>> likedList = new ArrayList<>();
        int count = 1;
        while (paginationToken != null) {
            HttpResponse<String> twitterAPIResponse = null;
            String amendedURL = baseRetrievalURL;
            if (!paginationToken.isEmpty()) {
                amendedURL = amendedURL + "?pagination_token=" + paginationToken;
            }
            twitterAPIResponse = this.executeTwitterAPIRequest(amendedURL);
            JSONObject responseHeaders = new JSONObject(twitterAPIResponse.headers().map());
            Logger.info(twitterAPIResponse);
            Logger.info(responseHeaders);
            if (Integer.parseInt(responseHeaders.getJSONArray("x-rate-limit-remaining").get(0).toString()) == 0) {
                try {
                    this.pauseExecution(responseHeaders.getJSONArray("x-rate-limit-reset").get(0).toString());
                } catch (Exception e) {
                    Logger.error(responseHeaders.toString());
                    e.printStackTrace();
                }
            }
            JSONObject responseJSON = new JSONObject(twitterAPIResponse.body());
            likedList.add(this.parseLikedTweetResponse(responseJSON));
            Logger.info("Retrieved PAGE {} of TWEETS for {} with pagination {}", count++, username, paginationToken);
            paginationToken = this.getNextPaginationToken(responseJSON);
        }
        return likedList;
    }

    private String extractTweetLink(JSONObject tweetJSON) {
        String tweetText = tweetJSON.getString("text");
        int lastSpaceLoc = tweetText.lastIndexOf(" ") + 1;
        return tweetText.substring(lastSpaceLoc);
    }

    private boolean mediaExists(String mediaPath) {
        File f = new File(mediaPath);
        return f.exists() && !f.isDirectory();
    }

    private void downloadTweetMedia(String tweetID, String dirPath) {
        String tweetURL = "https://api.twitter.com/2/tweets/" + tweetID + "?tweet.fields=author_id,created_at&expansions=attachments.media_keys,author_id,entities.mentions.username&media.fields=url";
        HttpResponse<String> tweetResponse = executeTwitterAPIRequest(tweetURL);
        JSONObject jsonResponse = new JSONObject(tweetResponse.body());
        if (jsonResponse.has("errors") || !jsonResponse.has("data") || !jsonResponse.has("includes")) {
            Logger.info("RESOURCE contains errors or no data: {} ", jsonResponse);
            return;
        }
        JSONObject responseHeaders = new JSONObject(tweetResponse.headers().map());
        if (Integer.parseInt(responseHeaders.getJSONArray("x-rate-limit-remaining").getString(0)) == 0) {
            try {
                this.pauseExecution(responseHeaders.getJSONArray("x-rate-limit-reset").getString(0));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        JSONObject tweetData = jsonResponse.getJSONObject("data");
        JSONObject additionalData = jsonResponse.getJSONObject("includes");
        if (!additionalData.has("media") || additionalData.getJSONArray("media").length() == 0) {
            Logger.info("RESOURCE has no media to download [id: {}]", tweetData.getString("id"));
            return;
        }
        JSONArray mediaArr = additionalData.getJSONArray("media");
        String tweetUsername = additionalData.getJSONArray("users").getJSONObject(0).getString("username");
        for (int index = 0; index < mediaArr.length(); index++) {
            String mediaObjType = mediaArr.getJSONObject(index).getString("type");
            if (mediaObjType.equals("video")) {
                String videoTitle = tweetUsername + "_" + tweetID;
                if (mediaExists(dirPath + "\\" + videoTitle + ".mp4")) {
                    Logger.info("VIDEO {} exists already.", videoTitle + ".mp4");
                    return;
                }
                Logger.trace("Executing YOUTUBE-DL VIDEO PROCESS {}", "youtube-dl-o '" + videoTitle + ".%(ext)s' " + extractTweetLink(tweetData));
                executeProcess(dirPath, "youtube-dl -o '" + videoTitle + ".%(ext)s' " + extractTweetLink(tweetData));
                Logger.info("VIDEO \"{}\" downloaded to location {}", videoTitle, dirPath);
            } else if (mediaObjType.equals("photo")) {
                String imageTitle = tweetUsername + "_" + tweetID + "_" + (index + 1) + ".jpg";
                if (mediaExists(dirPath + "\\" + imageTitle)) {
                    Logger.info("PHOTO {} exists already.", imageTitle);
                    continue;
                }
                JSONObject mediaObj = mediaArr.getJSONObject(index);
                String imageURL = mediaObj.getString("url");
                String imageCreationDate = tweetData.getString("created_at");
                String wgetCmd = "wget -O '" + imageTitle + "' -o /dev/null \"" + imageURL + "\"";
                String exifCmd = "exiftool \"-FileModifyDate=" + imageCreationDate + "\" " + "\"./" + imageTitle + "\"";
                Logger.trace("Executing WGET PHOTO PROCESS {}", wgetCmd);
                executeProcess(dirPath, wgetCmd);
                executeProcess(dirPath, exifCmd);
                Logger.info("PHOTO \"{}\" downloaded to location {}\\{}", imageTitle, dirPath, imageTitle);
            } else {
            /*Gifs are included here. For most purposes, nobody wants gifs.
             Many are just reactions that are repetitious and unnecessary for our purposes
             */
                Logger.info("INVALID MEDIA type or other DATA: {}", tweetData.getString("id"));
            }

        }
    }

    private List<String> parseLikedTweetResponse(JSONObject likedTweetsResponse) {
        List<String> tweetList = new ArrayList<>();
        if (likedTweetsResponse.has("data")) {
            JSONArray tweetArray = likedTweetsResponse.getJSONArray("data");
            for (int index = 0; index < tweetArray.length(); index++) {
                if (tweetArray.getJSONObject(index).has("id")) {
                    String curTweetID = tweetArray.getJSONObject(index).getString("id");
                    tweetList.add(curTweetID);
                }
            }
        } else {
            Logger.error("Non-data response {}", likedTweetsResponse.toString());
        }
        return tweetList;
    }


    private List<String> getUsernames(String filePath) {
        Logger.info("Retrieving USERS from {}", filePath);
        List<String> usernames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line = reader.readLine();
            while (line != null) {
                String username = line.strip();
                usernames.add(username);
                line = reader.readLine();
            }
        } catch (FileNotFoundException fne) {
            System.err.println("Could not find the file specified.");
            System.exit(-1);
        } catch (IOException ioe) {
            System.err.println("There was a general io exception.");
        }
        Logger.info("{} users retrieved from {}", usernames.size(), filePath);
        return usernames;
    }

    public boolean userExistsByUsername(String username) {
        String requestURL = "https://api.twitter.com/2/users/by/username/" + username;
        HttpResponse<String> twitterAPIResponse = executeTwitterAPIRequest(requestURL);
        JSONObject responseObj = new JSONObject(twitterAPIResponse.body());
        if (responseObj.has("errors")) {
            String errorReason = responseObj.getJSONArray("errors").getJSONObject(0).getString("detail");
            Logger.info("USER {} could not be found. {}", username, errorReason);
            return false;
        }
        return true;
    }

    public String getUserIDbyUsername(String username) {
        String userIdUrl = "https://api.twitter.com/2/users/by/username/" + username;
        HttpResponse<String> idResponse = executeTwitterAPIRequest(userIdUrl);
        JSONObject responseJSON = new JSONObject(idResponse.body());
        if (responseJSON.has("data") && responseJSON.getJSONObject("data").has("id")) {
            String userID = responseJSON.getJSONObject("data").getString("id");
            Logger.trace("Retrieved USER {}'s id: {}", username, userID);
            return userID;
        } else {
            Logger.trace("User id for USER {} could not be retrieved.", username);
            return null;
        }
    }

    public List<List<String>> retrieveTweets(String username, List<String> excludes) {
        String userID = getUserIDbyUsername(username);
        String baseRetrievalURL = "https://api.twitter.com/2/users/" + userID + "/tweets?expansions=author_id&max_results=100";
        if (!excludes.isEmpty()) {
            StringBuilder excludeString = new StringBuilder("&exclude=");
            for (String exclusion : excludes) {
                excludeString.append(exclusion).append(",");
            }
            baseRetrievalURL = baseRetrievalURL + excludeString.substring(0, excludeString.length() - 1);
        }
        String paginationToken = "";
        List<List<String>> tweetList = new ArrayList<>();
        int count = 1;
        while (paginationToken != null) {
            HttpResponse<String> twitterAPIResponse = null;
            String amendedURL = baseRetrievalURL;
            if (!paginationToken.isEmpty()) {
                amendedURL = amendedURL + "&pagination_token=" + paginationToken;
            }
            Logger.info("USER {}'s tweets being retrieved at RESOURCE {}", username, amendedURL);
            twitterAPIResponse = this.executeTwitterAPIRequest(amendedURL);
            JSONObject responseHeaders = new JSONObject(twitterAPIResponse.headers().map());
            if (Integer.parseInt(responseHeaders.getJSONArray("x-rate-limit-remaining").get(0).toString()) == 0) {
                try {
                    this.pauseExecution(responseHeaders.getJSONArray("x-rate-limit-reset").get(0).toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            JSONObject responseJSON = new JSONObject(twitterAPIResponse.body());
            tweetList.add(this.parseRetrievedTweetResponse(responseJSON));
            Logger.info("Retrieved PAGE {} of TWEETS for {} with pagination {}", count++, username, paginationToken);
            paginationToken = this.getNextPaginationToken(responseJSON);
        }
        return tweetList;
    }


    private List<String> parseRetrievedTweetResponse(JSONObject likedTweetsResponse) {
        List<String> tweetList = new ArrayList<>();
        if (likedTweetsResponse.has("data")) {
            JSONArray tweetArray = likedTweetsResponse.getJSONArray("data");
            for (int index = 0; index < tweetArray.length(); index++) {
                JSONObject curTweet = tweetArray.getJSONObject(index);
                if (curTweet.has("id") && !curTweet.has("referenced_tweets")) {
                    String curTweetID = tweetArray.getJSONObject(index).getString("id");
                    tweetList.add(curTweetID);
                }
                if (curTweet.has("id") && curTweet.has("referenced_tweets")) {
                    Logger.debug("A Tweet was referenced here {}", tweetArray.getJSONObject(index));
                }
            }
        } else {
            Logger.error("RESPONSE contained no data: {}", likedTweetsResponse.toString());
        }
        return tweetList;
    }

    public int deepListSize(List<List<String>> listOfLists) {
        int size = 0;
        for (List<String> list : listOfLists) {
            size += list.size();
        }
        return size;
    }

    private void createDirPath(String dirPath) {
        boolean creationResult = new File(dirPath).mkdirs();
        if (!creationResult) {
            Logger.info("DIRECTORY {} did not exist. Created now.", dirPath);
        }
    }

    public List<String> getDirectoryFiles(String dirPath) {
        List<String> files = new ArrayList<>();
        final File folder = new File(dirPath);
        for (final File fileEntry : folder.listFiles()) {
            files.add(fileEntry.getName());
        }
        return files;
    }

    public void getLikedMedia(String username, String baseDirPath) {
        String userID = getUserIDbyUsername(username);
        List<List<String>> likedTweets = this.retrieveLikedTweets(userID);
        String userDirPath = baseDirPath + username;
        this.createDirPath(userDirPath);
        List<String> existingFiles = this.getDirectoryFiles(userDirPath);
        for (List<String> tweetList : likedTweets) {
            tweetLoop:
            for (String tweet : tweetList) {
                for (String file : existingFiles) {
                    if (file.contains(tweet)) {
                        Logger.info("Found RESOURCE currently exists in some way [{} : {}]", file, tweet);
                        continue tweetLoop;
                    }
                }
                this.downloadTweetMedia(tweet, userDirPath);
            }
        }

    }

    public void getUsersMedia(String usernameFilePath, String baseDirPath, List<String> tweetExclusions) {
        Logger.info("Retrieving the tweets of USERS from FILE {}", usernameFilePath);
        List<String> usernames = this.getUsernames(usernameFilePath);
        for (String username : usernames) {
            Logger.info("Retrieving media from USER {}", username);
            if (!this.userExistsByUsername(username)) {
                continue;
            }
            String userDirPath = baseDirPath + username;
            this.createDirPath(userDirPath);
            List<String> existingFiles = this.getDirectoryFiles(userDirPath);
            List<List<String>> tweets = this.retrieveTweets(username, tweetExclusions);
            Logger.info("Will retrieve USER {}'s MEDIA from {} tweets", username, this.deepListSize(tweets));
            for (List<String> tweetList : tweets) {
                tweetLoop:
                for (String tweet : tweetList) {
                    for (String file : existingFiles) {
                        if (file.contains(tweet)) {
                            Logger.info("Found RESOURCE currently exists in some way [{} : {}]", file, tweet);
                            continue tweetLoop;
                        }
                    }
                    this.downloadTweetMedia(tweet, userDirPath);
                }
            }
        }
    }


    public static void main(String[] args) {
        String bearerToken = args[0];
        String filePath = args[1];
        String username = "";
        String likedMediaPath = "";
        String userMediaPath = "";
        List<String> exclusions = new ArrayList<>();
        //exclusions.add("retweets");
        exclusions.add("replies");
        UranusExporter test = new UranusExporter(bearerToken);
        test.getLikedMedia(username, likedMediaPath);
        test.getUsersMedia(filePath, userMediaPath, exclusions);
    }

}
