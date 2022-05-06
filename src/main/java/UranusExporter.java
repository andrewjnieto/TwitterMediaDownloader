import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class UranusExporter {
    private static HttpClient httpClient;
    private String bearerHeaderValue;
    private String userID;

    private UranusExporter(String userID, String bearerToken) {
        this.httpClient = HttpClient.newHttpClient();
        if (bearerToken == null || bearerToken.isEmpty() || bearerToken.matches("\\s+")) {
            throw new IllegalArgumentException("The bearer token is not valid: " + bearerToken);
        }
        if (userID == null || userID.isEmpty() || userID.matches("\\s+")) {
            throw new IllegalArgumentException("The user id is not valid: " + userID);
        }
        this.bearerHeaderValue = "Bearer " + bearerToken;
        this.userID = userID;
    }

    public HttpResponse<String> executeTwitterAPIRequest(String resourceURL) {
        var request = HttpRequest.newBuilder(URI.create(resourceURL))
                .GET()
                .headers("Authorization", this.bearerHeaderValue)
                .build();
        HttpResponse<String> httpResponse = null;
        try {
            httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException | IOException e) {
            System.err.println("The request was interrupted.");
            e.printStackTrace();
        }
        return httpResponse;
    }

    private List<String> parseLikedTweetsResponse(JSONObject likedTweetsResponse) {
        List<String> tweetList = new ArrayList<String>();
        if (likedTweetsResponse.has("data")) {
            JSONArray tweetArray = likedTweetsResponse.getJSONArray("data");
            for (int index = 0; index < tweetArray.length(); index++) {
                if (tweetArray.getJSONObject(index).has("id")) {
                    String curTweetID = tweetArray.getJSONObject(index).getString("id");
                    tweetList.add(curTweetID);
                }
            }
        } else {
            System.err.println("Encountered response with no data: " + likedTweetsResponse.toString());
        }
        return tweetList;
    }


    private String getNextPaginationToken(JSONObject likedTweetsResponse) {
        JSONObject metadata = likedTweetsResponse.getJSONObject("meta");
        if (!metadata.has("next_token")) {
            return null;
        }
        return metadata.getString("next_token");
    }

    public void pauseExecution(String epochTime) throws Exception {
        //consider making this part of the main class as static
        DateFormat dateFormat = new SimpleDateFormat("hh:mm:ss a");
        Calendar cal = Calendar.getInstance();
        Date epochDate = new Date(Long.parseLong(epochTime) * 1000);
        Date currentTime = new Date();
        System.out.println("The current time is: " + dateFormat.format(cal.getTime()));
        long timeToWait = (epochDate.getTime() - currentTime.getTime()) + 5000;
        if (timeToWait > Integer.MAX_VALUE) {
            throw new Exception("The number of milliseconds to wait exceeds that of the allowed range.");
        }
        cal.add(Calendar.MILLISECOND, (int) timeToWait);
        System.out.println("Execution paused until " + dateFormat.format(cal.getTime()) + " due to rate limit.");
        if (timeToWait > 0) {
            Thread.sleep(timeToWait);
        }
    }

    private void downloadMedia(String dirPath, String cmdArgs) {
        if (dirPath == null || dirPath.isEmpty()) {
            dirPath = ".";
        }
        if (!Files.isDirectory(Paths.get(dirPath))) {
            System.err.println("The directory path does not exist, is not valid, or cannot be determined: " + dirPath);
            return;
        }
        String[] processArgs = new String[]{"bash", "-c", cmdArgs};
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(processArgs, null, new File(dirPath));
        } catch (IOException ioe) {
            System.err.println("There was an error downloading the media.");
            ioe.printStackTrace();
        }
//        String processOutput = readProcessOutput(process);
//        if(!processOutput.isEmpty()) {
//            System.err.println(processOutput);
//        }
        //System.out.println(readProcessOutput(process));
    }

    private void changeMediaInfo(String dirPath, String filePath, String cmdArgs) {
        //FIX WITH THIS ASAP: https://stackoverflow.com/questions/43972777/java-nio-file-invalidpathexception-illegal-char-at-index-2
//        if (filePath == null || filePath.isEmpty() || Files.exists(Paths.get(filePath))) {
//            System.err.println("The file path does not exist, is not valid, or cannot be determined: " + filePath);
//            return;
//        }
        String[] processArgs = new String[]{"bash", "-c", cmdArgs};
        try {
            Process process = Runtime.getRuntime().exec(processArgs, null, new File(dirPath));
        } catch (IOException ioe) {
            System.err.println("There was an error downloading the media.");
            ioe.printStackTrace();
        }
        //readProcessOutput(process);
    }

    private String extractTweetLink(JSONObject tweetJSON) {
        String tweetText = tweetJSON.getString("text");
        int lastSpaceLoc = tweetText.lastIndexOf(" ") + 1;
        return tweetText.substring(lastSpaceLoc);
    }

    private String extractTweetText(JSONObject tweetJSON) {
        String tweetText = tweetJSON.getString("text");
        int lastSpaceLoc = tweetText.lastIndexOf(" ");
        if (lastSpaceLoc == -1) {
            return "";
        }
        return tweetText.substring(0, lastSpaceLoc);
    }
    private String readProcessOutput(Process process) {
        StringBuilder stdError = new StringBuilder();
        BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
        try {
            String line;
            while ((line = errorReader.readLine()) != null) {
                stdError.append(line + "\n");
            }
            int exitVal = process.waitFor();
            if (exitVal != 0) {
                return stdError.toString().strip();
            }else{
                return "";
            }
        } catch (IOException ioe) {
            System.err.println("There was an error reading the process results.");
            ioe.printStackTrace();
        } catch (InterruptedException ie) {
            System.err.println("The reader(s) was interrupted.");
            ie.printStackTrace();
        }
        return null;
    }
    private void downloadTweetMedia(HttpResponse<String> tweet, String dirPath) {
        JSONObject JSONResponse = new JSONObject(tweet.body());
        if (JSONResponse.has("errors") || !JSONResponse.has("data") || !JSONResponse.has("includes")) {
            System.err.println("A tweet was found with no data/media: " + JSONResponse.toString());
            return;
        }
        JSONObject tweetData = JSONResponse.getJSONObject("data");
        JSONObject additionalData = JSONResponse.getJSONObject("includes");
        JSONObject responseHeaders = new JSONObject(tweet.headers().map());
        if (Integer.parseInt(responseHeaders.getJSONArray("x-rate-limit-remaining").get(0).toString()) == 0) {
            try {
                this.pauseExecution(responseHeaders.getJSONArray("x-rate-limit-reset").get(0).toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (!additionalData.has("media") || additionalData.getJSONArray("media").length() == 0) {
            System.err.println("This tweet contains no media to download: (" + tweetData.getString("id") + ")");
            return;
        }
        JSONArray mediaArr = additionalData.getJSONArray("media");
        String tweetUsername = additionalData.getJSONArray("users").getJSONObject(0).getString("username");
        String tweetID = tweetData.getString("id");
        //add support for gifs later also fix the way video titles are displayed when downloaded.
        if (mediaArr.getJSONObject(0).getString("type").equals("video")) {
            String videoTitle = tweetUsername + "_" + tweetID;
            System.out.println("yt-dlp -o \"" + videoTitle + "\" " + extractTweetLink(tweetData));
            downloadMedia(dirPath, "yt-dlp -o \'" + videoTitle + ".%(ext)s\' " + extractTweetLink(tweetData));
            System.out.println("Video \"" + videoTitle + "\" downloaded to \'" + dirPath + "\'");
        } else if (mediaArr.getJSONObject(0).getString("type").equals("photo")) {
            for (int index = 0; index < mediaArr.length(); index++) {
                JSONObject mediaObj = mediaArr.getJSONObject(index);
                String imageURL = mediaObj.getString("url");
                String imageCreationDate = tweetData.getString("created_at");
                String imageTitle = tweetUsername + "_" + tweetID + "_" + (index + 1) + ".jpg";
                String wgetCmd = "wget -O \'" + imageTitle + "\' -o /dev/null \"" + imageURL + "\"";
                String exifCmd = "exiftool \"-FileModifyDate=" + imageCreationDate + "\" " + "\"./" + dirPath + "/" + imageTitle + "\"";
                downloadMedia(dirPath, wgetCmd);
                changeMediaInfo(".", "/" + dirPath + "/" + imageTitle, exifCmd);
                System.out.println("Photo \"" + imageTitle + "\" downloaded to \'" + dirPath + "\'");
            }
        } else {
            System.out.println("Invalid Type of data: " + tweetData);
        }
    }

    public void downloadTweetMedia(String tweetID, String dirPath) {
        String tweetURL = "https://api.twitter.com/2/tweets/" + tweetID + "?tweet.fields=author_id,created_at&expansions=attachments.media_keys,author_id,entities.mentions.username&media.fields=url";
        downloadTweetMedia(executeTwitterAPIRequest(tweetURL), dirPath);
    }

    public List<List<String>> retrieveLikedTweets() {
        String likedTweetRequestURL = "https://api.twitter.com/2/users/" + this.userID + "/liked_tweets";
        String paginationToken = "";
        List<List<String>> likedList = new ArrayList<List<String>>();
        int count = 0;
        do {

            HttpResponse<String> twitterAPIResponse = null;
            if (paginationToken.isEmpty()) {
                twitterAPIResponse = this.executeTwitterAPIRequest(likedTweetRequestURL);
            } else {
                twitterAPIResponse = this.executeTwitterAPIRequest(likedTweetRequestURL + "?pagination_token=" + paginationToken);
            }
            JSONObject responseJSON = new JSONObject(twitterAPIResponse.body());
            JSONObject responseHeaders = new JSONObject(twitterAPIResponse.headers().map());
            likedList.add(this.parseLikedTweetsResponse(responseJSON));
            if (Integer.parseInt(responseHeaders.getJSONArray("x-rate-limit-remaining").get(0).toString()) == 0) {
                try {
                    this.pauseExecution(responseHeaders.getJSONArray("x-rate-limit-reset").get(0).toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            System.out.println(count++ + " pagination=" + paginationToken);
            paginationToken = this.getNextPaginationToken(responseJSON);

        } while (paginationToken != null);
        return likedList;
    }

    public static void main(String[] args) throws Exception {
        String userID = args[0];
        String bearerToken = args[1];
        UranusExporter test = new UranusExporter(userID, bearerToken);
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("liked_tweet_ids.txt"))) {
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null && count < 75) {
                sb.append(line);
                sb.append(System.lineSeparator());
                line = br.readLine();
                test.downloadTweetMedia(line, "C:\\Users\\nietoaj\\Documents\\( ͡° ͜ʖ ͡°)");
                count++;
            }
            String everything = sb.toString();
            //System.out.println(everything);
        }
//        List<List<String>> likedList = test.retrieveLikedTweets();
//        int totalCount = 0;
//        for (List<String> list : likedList) {
//            for (String tweet : list) {
//                System.out.println(tweet);
//                ++totalCount;
//            }
//        }
//        System.out.println(totalCount);


    }
}