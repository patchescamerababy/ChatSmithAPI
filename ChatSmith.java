import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.json.*;
import com.sun.net.httpserver.*;

public class ChatSmith {
    public static int port = 80;
    private static String accessToken = null;
    private static long tokenExpiration = 0;
    private static final String IMAGE_DIRECTORY = "images";
    public static int MAX_IMAGE_WIDTH = 2000;
    public static int MAX_IMAGE_HEIGHT = 2000;
    private static final int MAX_IMAGES = 5; // 最大保存图片数量
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 创建并返回一个HttpServer实例，尝试绑定到指定端口
     */
    public static HttpServer createHttpServer(int initialPort) throws IOException {
        int port = initialPort;
        HttpServer server = null;

        // 循环尝试找到一个可用的端口
        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
                ChatSmith.port = port;
            } catch (BindException e) {
                if (port < 65535) {
                    System.out.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++; // 端口号加1
                } else {
                    System.err.println("All ports from " + initialPort + " to 65535 are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }

    public static void main(String[] args) throws IOException {
        // 创建图片存储目录（如果不存在）
        createImageDirectory();
        int port = 80;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();

        HttpServer server = createHttpServer(port);
        server.createContext("/v1/chat/completions", new CompletionHandler());
        server.createContext("/", new CompletionHandler());

        server.setExecutor(executor); // 分配虚拟线程执行器给服务器
        server.start();
    }

    private static void createImageDirectory() throws IOException {
        Path imageDir = Paths.get(IMAGE_DIRECTORY);
        if (!Files.exists(imageDir)) {
            Files.createDirectory(imageDir);
            System.out.println("Created image directory at: " + imageDir.toAbsolutePath());
        }
    }

    public static synchronized CompletableFuture<String[]> getTokenArrayAsync() {
        JSONObject jsonInput = new JSONObject();
        jsonInput.put("device_id", "3D773885E5B4537A");
        jsonInput.put("order_id", "");
        jsonInput.put("product_id", "");
        jsonInput.put("purchase_token", "");
        jsonInput.put("subscription_id", "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.vulcanlabs.co/smith-auth/api/v1/token"))
                .header("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt")
                .header("Accept", "application/json")
                .header("User-Agent", "Chat Smith Android, Version 3.9.5(669)")
                .header("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonInput.toString()))
                .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            JSONObject jsonNode = new JSONObject(response.body());
                            String newAccessToken = jsonNode.getString("AccessToken");
                            String expirationStr = jsonNode.getString("AccessTokenExpiration");
                            long newExpiration = Instant.parse(expirationStr).getEpochSecond();
                            return new String[]{newAccessToken, String.valueOf(newExpiration)};
                        } catch (JSONException e) {
                            System.err.println("JSON 解析错误: " + e.getMessage());
                            return null;
                        }
                    } else {
                        System.err.println("Token 请求失败，响应码: " + response.statusCode());
                        try {
                            String errorBody = response.body();
                            System.err.println("错误响应体: " + errorBody);
                        } catch (Exception e) {
                            System.err.println("无法读取错误响应体: " + e.getMessage());
                        }
                        return null;
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                });
    }

    public static synchronized CompletableFuture<String> getValidTokenAsync() {
        long currentTime = Instant.now().getEpochSecond();
        if (accessToken == null || currentTime >= tokenExpiration) {
            return getTokenArrayAsync().thenApply(tokenData -> {
                if (tokenData != null) {
                    accessToken = tokenData[0];
                    tokenExpiration = Long.parseLong(tokenData[1]);
                    System.out.println("Got new token: " + accessToken);
                    return accessToken;
                } else {
                    System.err.println("无法获取 token");
                    return null;
                }
            });
        } else {
            return CompletableFuture.completedFuture(accessToken);
        }
    }

    static class CompletionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 设置 CORS 头
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            String requestMethod = exchange.getRequestMethod().toUpperCase();

            if (requestMethod.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equals(requestMethod)) {
                // 返回欢迎页面
                String response = "<html><head><title>Welcome to the ChatGPT API</title></head><body><h1>Welcome to the ChatGPT API</h1><p>This API is used to interact with the ChatGPT model. You can send messages to the model and receive responses.";

                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (!"POST".equals(requestMethod)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // 异步处理请求
            CompletableFuture.runAsync(() -> {
                try {
                    InputStream is = exchange.getRequestBody();
                    String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                            .lines()
                            .reduce("", (acc, line) -> acc + line);

                    getValidTokenAsync().thenAccept(token -> {
                        if (token == null) {
                            System.out.println("Failed to get a valid token.");
                            sendError(exchange, "无法获取有效的 token");
                            return;
                        }

                        try {
                            JSONObject requestJson = new JSONObject(requestBody);

                            boolean hasImageUrl = false;
                            String visionApiResponse = null;
                            String savedFilePath = null;
                            StringBuilder contentBuilder = new StringBuilder();

                            if (requestJson.has("messages")) {
                                JSONArray messages = requestJson.getJSONArray("messages");
                                Iterator<Object> iterator = messages.iterator();
                                while (iterator.hasNext()) {
                                    JSONObject message = (JSONObject) iterator.next();
                                    if (message.has("content")) {
                                        Object contentObj = message.get("content");
                                        if (contentObj instanceof JSONArray) {
                                            JSONArray contentArray = (JSONArray) contentObj;
                                            for (int j = 0; j < contentArray.length(); j++) {
                                                JSONObject contentItem = contentArray.getJSONObject(j);
                                                if (contentItem.has("text")) {
                                                    contentBuilder.append(contentItem.getString("text"));
                                                }
                                                if (contentItem.has("type") && "image_url".equals(contentItem.getString("type"))) {
                                                    hasImageUrl = true;
                                                    JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                                    String dataUrl = imageUrlObj.getString("url");
                                                    savedFilePath = decodeAndSaveImage(dataUrl);

                                                    if (savedFilePath != null) {
                                                        visionApiResponse = sendImagePostRequest(savedFilePath, contentBuilder.toString());
                                                    }
                                                }
                                                if (j < contentArray.length() - 1) {
                                                    contentBuilder.append(" ");
                                                }
                                            }
                                            String extractedContent = contentBuilder.toString().trim();
                                            if (extractedContent.isEmpty()) {
                                                iterator.remove();
                                                System.out.println("Deleted message with empty content.");
                                            } else {
                                                message.put("content", extractedContent);
                                                System.out.println("Extracted and replaced content: " + extractedContent);
                                            }
                                        } else if (contentObj instanceof String) {
                                            String contentStr = ((String) contentObj).trim();
                                            if (contentStr.isEmpty()) {
                                                iterator.remove();
                                                System.out.println("Deleted message with empty content.");
                                            } else {
                                                message.put("content", contentStr);
                                                System.out.println("Retained content: " + contentStr);
                                            }
                                        } else {
                                            iterator.remove();
                                            System.out.println("Deleted non-expected type of content message.");
                                        }
                                    }
                                }

                                if (messages.length() == 0) {
                                    sendError(exchange, "所有消息的内容均为空。");
                                    return;
                                }
                            }

                            boolean isStream = requestJson.optBoolean("stream", false);
                            if (hasImageUrl) {
                                if (visionApiResponse != null) {
                                    JSONObject visionJson;
                                    try {
                                        visionJson = new JSONObject(visionApiResponse);
                                    } catch (JSONException je) {
                                        System.err.println("Vision API 返回的响应不是有效的 JSON.");
                                        sendError(exchange, "Vision API 返回的响应不是有效的 JSON.");
                                        return;
                                    }

                                    if (isStream) {
                                        handleVisionStreamResponse(exchange, visionJson);
                                    } else {
                                        JSONObject openAIResponse = buildOpenAIResponse(visionJson);
                                        String openAIResponseString = openAIResponse.toString();

                                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                                        exchange.sendResponseHeaders(200, openAIResponseString.getBytes(StandardCharsets.UTF_8).length);
                                        try (OutputStream os = exchange.getResponseBody()) {
                                            os.write(openAIResponseString.getBytes(StandardCharsets.UTF_8));
                                        }
                                    }

                                    System.out.println("Returned Vision API response and skipped normal chat completion processing.");
                                    return;
                                } else {
                                    sendError(exchange, "处理图像时发生错误。");
                                    return;
                                }
                            }

                            if (isStream) {
                                requestJson.remove("stream");
                                System.out.println("Stream request detected. Removed 'stream' field.");
                            }

                            requestJson.put("nsfw_check", false);

                            if (requestJson.has("temperature")) {
                                double tempDouble = requestJson.getDouble("temperature");
                                int tempInt = (int) Math.round(tempDouble);
                                requestJson.put("temperature", tempInt);
                                System.out.println("Converted temperature from " + tempDouble + " to " + tempInt);
                            } else {
                                requestJson.put("temperature", 1);
                                System.out.println("Temperature not provided, set to default value 1");
                            }

                            if (requestJson.has("top_p")) {
                                double topPDouble = requestJson.getDouble("top_p");
                                int topPInt = (int) Math.round(topPDouble);
                                requestJson.put("top_p", topPInt);
                                System.out.println("Converted top_p from " + topPDouble + " to " + topPInt);
                            } else {
                                requestJson.put("top_p", 1);
                                System.out.println("Top_p not provided, set to default value 1");
                            }

                            System.out.println("Stream mode: " + isStream);

                            String modifiedRequestBody = requestJson.toString();
                            System.out.println("Modified JSON to send to API: " + modifiedRequestBody);

                            if (isStream) {
                                handleStreamResponse(exchange, modifiedRequestBody, token);
                            } else {
                                handleNormalResponse(exchange, modifiedRequestBody, token);
                            }

                        } catch (JSONException je) {
                            je.printStackTrace();
                            sendError(exchange, "JSON 解析错误: " + je.getMessage());
                        } catch (Exception e) {
                            e.printStackTrace();
                            sendError(exchange, "内部服务器错误: " + e.getMessage());
                        }
                    }).exceptionally(ex -> {
                        ex.printStackTrace();
                        sendError(exchange, "内部服务器错误: " + ex.getMessage());
                        return null;
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, "内部服务器错误: " + e.getMessage());
                }
            }, Executors.newSingleThreadExecutor());
        }

        private void handleVisionStreamResponse(HttpExchange exchange, JSONObject visionJson) throws IOException {
            String assistantContent = "";
            if (visionJson.has("choices") && !visionJson.isNull("choices")) {
                MAX_IMAGE_WIDTH = 2000;
                MAX_IMAGE_HEIGHT = 2000;
                JSONArray choices = visionJson.getJSONArray("choices");
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("Message");
                    if (message == null)
                        continue;
                    if ("assistant".equalsIgnoreCase(message.optString("role", ""))) {
                        assistantContent = message.optString("content", "").trim();
                        break;
                    }
                }
            } else {
                MAX_IMAGE_HEIGHT -= 500;
                MAX_IMAGE_WIDTH -= 500;
                System.err.println("Vision API 响应中缺少 'choices' 字段或其为 null.");
                sendError(exchange, "Vision API 响应中缺少 'choices' 字段或其为 null.");
                return;
            }

            if (assistantContent.isEmpty()) {
                System.out.println("Assistant 的 content 为空，发送 [DONE]。");
                sendDone(exchange);
                return;
            }

            System.out.println("Extracted assistant content: \n" + assistantContent);

            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
            responseHeaders.add("Cache-Control", "no-cache");
            responseHeaders.add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                JSONObject sseJson = new JSONObject();
                JSONArray choicesArray = new JSONArray();

                JSONObject choiceObj = new JSONObject();
                choiceObj.put("index", 0);

                JSONObject contentFilterResults = new JSONObject();
                contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                choiceObj.put("content_filter_results", contentFilterResults);

                JSONObject delta = new JSONObject();
                delta.put("content", assistantContent);
                choiceObj.put("delta", delta);

                choicesArray.put(choiceObj);
                sseJson.put("choices", choicesArray);

                sseJson.put("created", visionJson.optLong("created", Instant.now().getEpochSecond()));
                sseJson.put("id", visionJson.optString("id", ""));
                sseJson.put("model", visionJson.optString("model", "gpt-4o"));
                sseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                String sseLine = "data: " + sseJson.toString() + "\n\n";
                os.write(sseLine.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String doneLine = "data: [DONE]\n\n";
                os.write(doneLine.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        private JSONObject buildOpenAIResponse(JSONObject apiResponse) {
            JSONObject adjustedResponse = new JSONObject();
            adjustedResponse.put("id", apiResponse.optString("id", ""));
            adjustedResponse.put("object", apiResponse.optString("object", ""));
            adjustedResponse.put("created", apiResponse.optLong("created", Instant.now().getEpochSecond()));
            adjustedResponse.put("model", "gpt-4o");

            JSONObject usage = apiResponse.optJSONObject("usage");
            if (usage != null) {
                adjustedResponse.put("usage", usage);
            }

            JSONArray choicesArray = apiResponse.optJSONArray("choices");
            JSONArray adjustedChoices = new JSONArray();
            if (choicesArray != null) {
                for (int i = 0; i < choicesArray.length(); i++) {
                    JSONObject choice = choicesArray.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("Message");
                    if (message == null)
                        continue;

                    String role = message.optString("role", "");
                    String content = message.optString("content", "").trim();
                    if (content.isEmpty())
                        continue;

                    JSONObject adjustedChoice = new JSONObject();
                    adjustedChoice.put("index", choice.optInt("index", i));

                    JSONObject contentFilterResults = new JSONObject();
                    contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                    contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                    adjustedChoice.put("content_filter_results", contentFilterResults);

                    adjustedChoice.put("finish_reason", choice.optString("finish_reason", ""));
                    JSONObject adjustedMessage = new JSONObject();
                    adjustedMessage.put("content", content);
                    adjustedMessage.put("role", role);
                    adjustedChoice.put("message", adjustedMessage);

                    adjustedChoices.put(adjustedChoice);
                }
            }
            adjustedResponse.put("choices", adjustedChoices);

            JSONArray promptFilterResults = new JSONArray();
            JSONObject promptFilterResult = new JSONObject();
            JSONObject promptContentFilterResults = new JSONObject();
            promptContentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptFilterResult.put("content_filter_results", promptContentFilterResults);
            promptFilterResult.put("prompt_index", 0);
            promptFilterResults.put(promptFilterResult);
            adjustedResponse.put("prompt_filter_results", promptFilterResults);

            adjustedResponse.put("system_fingerprint", "fp_67802d9a6d");

            return adjustedResponse;
        }

        private String decodeAndSaveImage(String dataUrl) {
            try {
                String[] parts = dataUrl.split(",");
                if (parts.length != 2) {
                    System.err.println("无效的 data URL 格式。");
                    return null;
                }
                String base64Data = parts[1];
                byte[] imageBytes = Base64.getDecoder().decode(base64Data);

                // 使用 ImageIO 读取图片为 BufferedImage
                ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes);
                BufferedImage originalImage = ImageIO.read(bis);
                if (originalImage == null) {
                    System.err.println("无法读取图片数据。");
                    return null;
                }

                // 调整图片尺寸
                BufferedImage resizedImage = resizeImage(originalImage, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT);

                // 生成唯一文件名
                String fileName = "image_" + UUID.randomUUID().toString() + System.currentTimeMillis() + ".png";
                Path outputPath = Paths.get(IMAGE_DIRECTORY, fileName);
                File outputFile = outputPath.toFile();

                // 保存调整后的图片
                ImageIO.write(resizedImage, "png", outputFile);

                System.out.println("图像已保存为: " + outputFile.getAbsolutePath());

                // 执行自动清理
                cleanupImages();

                return outputFile.getAbsolutePath();
            } catch (IllegalArgumentException e) {
                System.err.println("Base64 解码失败: " + e.getMessage());
                return null;
            } catch (IOException e) {
                System.err.println("保存图像文件失败: " + e.getMessage());
                return null;
            }
        }

        private String sendImagePostRequest(String filePath, String content) {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String LINE_FEED = "\r\n";

            try {
                URL url = new URL("https://api.vulcanlabs.co/smith-v2/api/v7/vision_android");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("X-Auth-Token", "DbzZaqCS/qzVIre/loqu2AShmtpU5E54znD9GSKkbcT/+jJ2KRFfBia55wzpDQqPzTOLdIkM/0c5zrei+RXk6uMyCeDJQ6HcJTRYtZOvC5vZXULvZK7gEM0tXy1UlQJtZoI37damlysxuNl/QwZ3khY0EtWRJOdRC2qIE90dNfo=");
                connection.setRequestProperty("Authorization", "Bearer " + getValidTokenSync());
                connection.setRequestProperty("X-Firebase-AppCheck-Error", "-9%3A+Integrity+API+error+%28-9%29%3A+Binding+to+the+service+in+the+Play+Store+has+failed.+This+can+be+due+to+having+an+old+Play+Store+version+installed+on+the+device.%0AAsk+the+user+to+update+Play+Store.%0A+%28https%3A%2F%2Fdeveloper.android.com%2Freference%2Fcom%2Fgoogle%2Fandroid%2Fplay%2Fcore%2Fintegrity%2Fmodel%2FIntegrityErrorCode.html%23CANNOT_BIND_TO_SERVICE%29.");
                connection.setRequestProperty("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Chat Smith Android, Version 3.9.5(669)");
                connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("Host", "api.vulcanlabs.co");
                connection.setRequestProperty("Connection", "Keep-Alive");

                try (OutputStream outputStream = connection.getOutputStream();
                     DataOutputStream writer = new DataOutputStream(outputStream)) {

                    // 构建 JSON 数据
                    JSONObject dataObject = new JSONObject();
                    dataObject.put("model", "gpt-4o");
                    dataObject.put("user", "71D1A17A547F1E22");
                    dataObject.put("nsfw_check", false);

                    JSONArray messagesArray = new JSONArray();
                    JSONObject messageObject = new JSONObject();
                    messageObject.put("role", "user");
                    messageObject.put("content", content);
                    messagesArray.put(messageObject);

                    dataObject.put("messages", messagesArray);

                    String jsonData = dataObject.toString();

                    // 添加 'data' 字段
                    writer.writeBytes("--" + boundary + LINE_FEED);
                    writer.writeBytes("Content-Disposition: form-data; name=\"data\"" + LINE_FEED);
                    writer.writeBytes("Content-Type: application/json; charset=utf-8" + LINE_FEED);
                    writer.writeBytes(LINE_FEED);
                    writer.write(jsonData.getBytes(StandardCharsets.UTF_8));
                    writer.writeBytes(LINE_FEED);

                    // 添加图片文件
                    writer.writeBytes("--" + boundary + LINE_FEED);
                    writer.writeBytes("Content-Disposition: form-data; name=\"images[]\"; filename=\"" + new File(filePath).getName() + "\"" + LINE_FEED);
                    writer.writeBytes("Content-Type: image/png" + LINE_FEED);
                    writer.writeBytes(LINE_FEED);

                    try (FileInputStream fis = new FileInputStream(filePath)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            writer.write(buffer, 0, bytesRead);
                        }
                    }
                    writer.writeBytes(LINE_FEED);

                    // 结束边界
                    writer.writeBytes("--" + boundary + "--" + LINE_FEED);
                    writer.flush();
                }

                // 处理响应
                int responseCode = connection.getResponseCode();
                InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                        connection.getInputStream() : connection.getErrorStream();

                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                    responseStream = new GZIPInputStream(responseStream);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

//                System.out.println("Received Response from Vision API: " + response.toString());
                return response.toString();

            } catch (IOException e) {
                System.err.println("发送图像 POST 请求失败: " + e.getMessage());
                return null;
            }
        }

        private String getValidTokenSync() {
            long currentTime = Instant.now().getEpochSecond();
            if (accessToken == null || currentTime >= tokenExpiration) {
                try {
                    CompletableFuture<String[]> future = getTokenArrayAsync();
                    String[] tokenData = future.get();
                    if (tokenData != null) {
                        accessToken = tokenData[0];
                        tokenExpiration = Long.parseLong(tokenData[1]);
                        System.out.println("Got new token: " + accessToken);
                    } else {
                        System.err.println("无法获取 token");
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }
            return accessToken;
        }

        private void handleNormalResponse(HttpExchange exchange, String requestBody, String token) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.vulcanlabs.co/smith-v2/api/v7/chat_android"))
                    .header("X-Auth-Token", "DaiExBn7Ib03PWRtbQu4HVQGUEGQKfA8GtrLN1oA8n4nOy9CdRu71OjKBwUZazZQxIgtCVQFCZtoBKgjuLVJpJTenTRjimRkaQUqZwtbXWjckIo3LeXut/Wslmkysgm9G0+lVxx38r0Eifu95+rIk5FMcZrQfZ+ubR0JkItOebU=")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Chat Smith Android, Version 3.9.5(669)")
                    .header("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            httpClient.sendAsync(request, BodyHandlers.ofString())
                    .thenAccept(response -> {
                        try {
                            String responseBody = response.body();
                            JSONObject apiResponse = new JSONObject(responseBody);

                            if (apiResponse.has("error") && !apiResponse.isNull("error")) {
                                JSONObject errorObj = apiResponse.getJSONObject("error");
                                String errorMessage = errorObj.optString("message", "未知错误");
                                sendError(exchange, "API 错误: " + errorMessage);
                                return;
                            }

                            JSONObject adjustedResponse = buildOpenAIResponse(apiResponse);
                            String adjustedResponseString = adjustedResponse.toString();

                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, adjustedResponseString.getBytes(StandardCharsets.UTF_8).length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(adjustedResponseString.getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (JSONException je) {
                            je.printStackTrace();
                            sendError(exchange, "目标 API 返回的响应不是有效的 JSON.");
                        } catch (IOException e) {
                            e.printStackTrace();
                            sendError(exchange, "响应发送失败: " + e.getMessage());
                        }
                    })
                    .exceptionally(ex -> {
                        ex.printStackTrace();
                        sendError(exchange, "请求失败: " + ex.getMessage());
                        return null;
                    });
        }

        private void handleStreamResponse(HttpExchange exchange, String requestBody, String token) throws IOException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.vulcanlabs.co/smith-v2/api/v7/chat_android"))
                    .header("X-Auth-Token", "DaiExBn7Ib03PWRtbQu4HVQGUEGQKfA8GtrLN1oA8n4nOy9CdRu71OjKBwUZazZQxIgtCVQFCZtoBKgjuLVJpJTenTRjimRkaQUqZwtbXWjckIo3LeXut/Wslmkysgm9G0+lVxx38r0Eifu95+rIk5FMcZrQfZ+ubR0JkItOebU=")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Chat Smith Android, Version 3.9.5(669)")
                    .header("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            httpClient.sendAsync(request, BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        try {
                            InputStream responseStream = (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip"))
                                    ? new GZIPInputStream(response.body())
                                    : response.body();

                            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                            StringBuilder responseBuilder = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                responseBuilder.append(line);
                            }

                            JSONObject apiResponse = new JSONObject(responseBuilder.toString());

                            if (apiResponse.has("error") && !apiResponse.isNull("error")) {
                                JSONObject errorObj = apiResponse.getJSONObject("error");
                                String errorMessage = errorObj.optString("message", "未知错误");
                                sendError(exchange, "API 错误: " + errorMessage);
                                return;
                            }

                            String assistantContent = "";
                            if (apiResponse.has("choices") && !apiResponse.isNull("choices")) {
                                JSONArray choices = apiResponse.getJSONArray("choices");
                                for (int i = 0; i < choices.length(); i++) {
                                    JSONObject choice = choices.getJSONObject(i);
                                    JSONObject message = choice.optJSONObject("Message");
                                    if (message == null)
                                        continue;
                                    if ("assistant".equalsIgnoreCase(message.optString("role", ""))) {
                                        assistantContent = message.optString("content", "").trim();
                                        break;
                                    }
                                }
                            } else {
                                System.err.println("API 响应中缺少 'choices' 字段或其为 null.");
                                sendError(exchange, "API 响应中缺少 'choices' 字段或其为 null.");
                                return;
                            }

                            if (assistantContent.isEmpty()) {
                                System.out.println("Assistant 的 content 为空，发送 [DONE]。");
                                sendDone(exchange);
                                return;
                            }

                            System.out.println("Extracted assistant content: " + assistantContent);

                            Headers responseHeaders = exchange.getResponseHeaders();
                            responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
                            responseHeaders.add("Cache-Control", "no-cache");
                            responseHeaders.add("Connection", "keep-alive");
                            exchange.sendResponseHeaders(200, 0);

                            try (OutputStream os = exchange.getResponseBody()) {
                                JSONObject sseJson = new JSONObject();
                                JSONArray choicesArray = new JSONArray();

                                JSONObject choiceObj = new JSONObject();
                                choiceObj.put("index", 0);

                                JSONObject contentFilterOffsets = new JSONObject();
                                contentFilterOffsets.put("check_offset", 790);
                                contentFilterOffsets.put("start_offset", 740);
                                contentFilterOffsets.put("end_offset", 846);
                                choiceObj.put("content_filter_offsets", contentFilterOffsets);

                                JSONObject contentFilterResults = new JSONObject();
                                contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                                contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                                contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                                contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                                contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                                choiceObj.put("content_filter_results", contentFilterResults);

                                JSONObject delta = new JSONObject();
                                delta.put("content", assistantContent);
                                choiceObj.put("delta", delta);

                                choicesArray.put(choiceObj);
                                sseJson.put("choices", choicesArray);

                                sseJson.put("created", apiResponse.optLong("created", Instant.now().getEpochSecond()));
                                sseJson.put("id", apiResponse.optString("id", ""));
                                sseJson.put("model", apiResponse.optString("model", "gpt-4o"));
                                sseJson.put("system_fingerprint", "fp_67802d9a6d");

                                String sseLine = "data: " + sseJson.toString() + "\n\n";
                                os.write(sseLine.getBytes(StandardCharsets.UTF_8));
                                os.flush();

                                String doneLine = "data: [DONE]\n\n";
                                os.write(doneLine.getBytes(StandardCharsets.UTF_8));
                                os.flush();
                            }
                        } catch (JSONException je) {
                            je.printStackTrace();
                            sendError(exchange, "目标 API 返回的响应不是有效的 JSON.");
                        } catch (IOException e) {
                            e.printStackTrace();
                            sendError(exchange, "响应发送失败: " + e.getMessage());
                        }
                    })
                    .exceptionally(ex -> {
                        ex.printStackTrace();
                        sendError(exchange, "请求失败: " + ex.getMessage());
                        return null;
                    });
        }

        private CompletableFuture<String> sendImagePostRequestAsync(String filePath, String content) {
            return CompletableFuture.supplyAsync(() -> sendImagePostRequest(filePath, content));
        }

        private void sendDone(HttpExchange exchange) throws IOException {
            OutputStream os = exchange.getResponseBody();
            String doneLine = "data: [DONE]\n\n";
            os.write(doneLine.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }

        private void sendError(HttpExchange exchange, String message) {
            try {
                JSONObject error = new JSONObject();
                error.put("error", message);
                byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private BufferedImage resizeImage(BufferedImage originalImage, int maxWidth, int maxHeight) {
            // 获取原始图像的宽度和高度
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            // 计算缩放比例
            double widthRatio = (double) maxWidth / originalWidth;
            double heightRatio = (double) maxHeight / originalHeight;
            double scaleFactor = Math.min(widthRatio, heightRatio);

            // 如果图像已经符合尺寸要求，则无需缩放
            if (scaleFactor >= 1.0) {
                return originalImage;
            }

            // 计算新的宽度和高度，保持纵横比
            int newWidth = (int) Math.round(originalWidth * scaleFactor);
            int newHeight = (int) Math.round(originalHeight * scaleFactor);

            // 使用imgscalr库缩放图像
            return Scalr.resize(originalImage, Scalr.Method.QUALITY, newWidth, newHeight);
        }

        // 新增：自动删除过期图片以保持目录中图片数量不超过MAX_IMAGES
        private void cleanupImages() {
            try {
                Path imageDir = Paths.get(IMAGE_DIRECTORY);
                if (!Files.exists(imageDir) || !Files.isDirectory(imageDir)) {
                    System.err.println("图片目录不存在或不是一个目录。");
                    return;
                }

                // 获取所有图片文件，按最后修改时间排序
                List<Path> imageFiles = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(imageDir, "*.png")) {
                    for (Path entry : stream) {
                        imageFiles.add(entry);
                    }
                }

                // 按最后修改时间升序排序（最早的在前）
                imageFiles.sort(Comparator.comparingLong(this::getLastModifiedTime));

                // 如果超过最大数量，则删除最早的文件
                while (imageFiles.size() > MAX_IMAGES) {
                    Path oldestFile = imageFiles.remove(0);
                    try {
                        Files.deleteIfExists(oldestFile);
                        System.out.println("已删除过期图片: " + oldestFile.toAbsolutePath());
                    } catch (IOException e) {
                        System.err.println("无法删除文件 " + oldestFile.toAbsolutePath() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("清理图片时发生错误: " + e.getMessage());
            }
        }

        private long getLastModifiedTime(Path path) {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return Long.MAX_VALUE;
            }
        }
    }
}
