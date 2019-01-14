package fr.openent.lool.helper;

import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import fr.openent.lool.bean.Token;
import fr.openent.lool.service.Impl.DefaultTokenService;
import fr.openent.lool.service.TokenService;
import fr.openent.lool.utils.Bindings;
import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.webutils.Either;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.user.UserUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class WopiHelper {

    private final Logger log = LoggerFactory.getLogger(WopiHelper.class);
    private String server;
    private HttpHelper httpHelper;
    private HttpClient httpClient;
    private EventBus eb;
    private final TokenService tokenService = new DefaultTokenService();
    private final String DISCOVER_COLLECTION = "lool_discover";

    public static final String tokenCollection = "wopi_token";

    public WopiHelper(Vertx vertx, String server) {
        try {
            this.httpHelper = new HttpHelper(vertx);
            this.eb = vertx.eventBus();
            this.server = server;
            this.httpClient = httpHelper.generateHttpClient(new URI(server));
        } catch (URISyntaxException e) {
            log.error("[WopiHelper@contructor] Failed to create WopiHelper", e);
        }
    }

    /**
     * Returns a Libre Office authentication token.
     *
     * @param request Client request
     * @param handler Function handler returning data
     */
    public void generateLoolToken(HttpServerRequest request, Handler<Either<String, Token>> handler) {
        new Token(eb, request, handler);
    }


    /**
     * Get action url from the discovery database
     *
     * @param contentType File content-type
     * @param action      Optional. User action
     * @param handler     Function handler returning data
     */
    public void getActionUrl(String contentType, String action, Handler<Either<String, String>> handler) {
        if (contentType == null) {
            handler.handle(new Either.Left<>("content-type  must be provided"));
            return;
        }
        JsonObject filter = new JsonObject()
                .put("content-type", contentType);

        if (action != null) {
            filter.put("action", action);
        }
        MongoDb.getInstance().findOne(DISCOVER_COLLECTION, filter, event -> {
            if ("ok".equals(event.body().getString("status"))) {
                JsonObject record = event.body();
                if (!record.containsKey("result")) {
                    handler.handle(new Either.Left<>("[WopiHelper@getActionUrl] Content-type doesn't match Libre Office Online capabilities"));
                    return;
                }
                handler.handle(new Either.Right<>(record.getJsonObject("result").getString("url")));
            } else {
                handler.handle(new Either.Left<>(event.body().getString("message")));
            }
        });
    }

    /**
     * Discover Libre Office Online format capabilities
     *
     * @param handler Function handler returning data
     */
    public void discover(Handler<Boolean> handler) {
        String discoverUri = "/hosting/discovery";
        HttpClientRequest req = httpClient.get(discoverUri, response -> {
            if (response.statusCode() != 200) {
                log.error("[WopiHelper@discover] An error occurred when discovering wopi api.");
            } else {
                Buffer responseBuffer = new BufferImpl();
                response.handler(responseBuffer::appendBuffer);
                response.endHandler(aVoid -> parseDiscover(responseBuffer, handler));
                response.exceptionHandler(throwable -> {
                    log.error(throwable);
                    handler.handle(false);
                });
            }
        });

        req.end();
    }

    public String getServer() {
        return server;
    }

    /**
     * Parse discover file
     *
     * @param buffer  discover file
     * @param handler Function handler returning data
     */
    private void parseDiscover(Buffer buffer, Handler<Boolean> handler) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource source = new InputSource(new StringReader(new String(buffer.getBytes())));
            Document xml = builder.parse(source);
            xml.getDocumentElement().normalize();

            NodeList actions = xml.getElementsByTagName("app");
            List<Future> futures = new ArrayList<>();

            Future<JsonObject> deleteFuture = Future.future();
            futures.add(deleteFuture);
            MongoDb.getInstance().delete(DISCOVER_COLLECTION, new JsonObject(), getDiscoverHandler(deleteFuture));

            for (int i = 0; i < actions.getLength(); i++) {
                Element app = (Element) actions.item(i);
                Element action = (Element) app.getElementsByTagName("action").item(0);
                String contentType = app.getAttribute("name");
                String extension = action.getAttribute("ext");
                String actionName = action.getAttribute("name");
                String urlSrc = action.getAttribute("urlsrc");

                JsonObject actionObject = new JsonObject()
                        .put("content-type", contentType)
                        .put("extension", extension)
                        .put("action", actionName)
                        .put("url", urlSrc);

                Future<JsonObject> future = Future.future();
                futures.add(future);
                MongoDb.getInstance().save(DISCOVER_COLLECTION, actionObject, getDiscoverHandler(future));
            }

            CompositeFuture.all(futures).setHandler(event -> handler.handle(event.succeeded()));

        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("[WopiHelper@parseDiscover] An error occurred while parsing discovery file", e);
            handler.handle(false);
        }
    }

    /**
     * Returns discover handler. It used to manage future callback
     *
     * @param future Future
     * @return Message handler
     */
    private Handler<Message<JsonObject>> getDiscoverHandler(Future<JsonObject> future) {
        return event -> {
            if ("ok".equals(event.body().getString("status"))) {
                future.complete(event.body());
            } else {
                future.fail(event.body().getString("message"));
            }
        };
    }


    /**
     * Encode string parameter as HTTP param using HttpHelper
     *
     * @param param parameter to encode
     * @return Parameter encoded as HTTP format
     */
    public String encodeWopiParam(String param) {
        return httpHelper.encode(param);
    }

    /**
     * Validate provided token. It check in token collection if token match session and file
     *
     * @param tokenId    Token identifier
     * @param documentId Document identifier
     * @param right      Right the user need. It should be a String from Binding enum.
     * @param handler    Function handler returning data
     */
    public void validateToken(String tokenId, String documentId, String right, Handler<JsonObject> handler) {
        tokenService.get(tokenId, documentId, tokenEvent -> {
            if (tokenEvent.isRight()) {
                if (tokenEvent.right().getValue().size() == 0) {
                    handler.handle(new JsonObject().put("valid", false).put("err", tokenEvent.left().getValue()));
                    return;
                }
                Token token = new Token(tokenEvent.right().getValue());
                UserUtils.getSession(eb, token.getSessionId(), session -> {
                    if (session == null || !token.getUser().equals(session.getString("userId"))) {
                        handler.handle(new JsonObject().put("valid", false).put("err", session == null ? "Session not found" : "Invalid user"));
                        return;
                    }
                    userCan(token.getSessionId(), documentId, right, can -> handler.handle(new JsonObject().put("valid", can).put("token", tokenEvent.right().getValue())));
                });
            } else {
                handler.handle(new JsonObject().put("valid", false));
            }
        });
    }

    /**
     * Check if the user can read file based on given session identifier and given document identifier
     *
     * @param sessionId  Session identifier
     * @param documentId Document identifier
     * @param handler    Function handler returning data
     */
    public void userCanRead(String sessionId, String documentId, Handler<Boolean> handler) {
        userCan(sessionId, documentId, Bindings.READ.toString(), handler);
    }

    /**
     * Check if the user can write file based on given session identifier and given document identifier
     *
     * @param sessionId  Session identifier
     * @param documentId Document identifier
     * @param handler    Function handler returning data
     */
    public void userCanWrite(String sessionId, String documentId, Handler<Boolean> handler) {
        userCan(sessionId, documentId, Bindings.CONTRIB.toString(), handler);
    }

    /**
     * Check if the user can use the file. Verification is based on given session identifier, given document identifier and given right.
     *
     * @param sessionId  Session identifier
     * @param documentId Document identifier
     * @param right      Right needed
     * @param handler    Function handler returning data
     */
    private void userCan(String sessionId, String documentId, String right, Handler<Boolean> handler) {
        UserUtils.getSession(eb, sessionId, session -> {
            if (session == null) {
                handler.handle(false);
                return;
            }

            List<DBObject> groups = new ArrayList<>();
            groups.add(QueryBuilder.start("userId").is(session.getString("userId"))
                    .put(right).is(true).get());
            JsonArray groupsIds = session.getJsonArray("groupsIds");
            for (int i = 0; i < groupsIds.size(); i++) {
                String gpId = groupsIds.getString(i);
                groups.add(QueryBuilder.start("groupId").is(gpId)
                        .put(right).is(true).get());
            }
            QueryBuilder query = QueryBuilder.start("_id").is(documentId).or(
                    QueryBuilder.start("owner").is(session.getString("userId")).get(),
                    QueryBuilder.start("shared").elemMatch(
                            new QueryBuilder().or(groups.toArray(new DBObject[groups.size()])).get()).get()
            );

            MongoDb.getInstance().count("documents", MongoQueryBuilder.build(query),
                    res -> handler.handle(res.body() != null && "ok".equals(res.body().getString("status")) && 1 == res.body().getInteger("count")));
        });
    }

    /**
     * Get Libre Office Online file capabilities
     *
     * @param handler Function handler returning data
     */
    public void getCapabilities(Handler<Either<String, JsonArray>> handler) {
        JsonObject query = new JsonObject();
        JsonObject sort = new JsonObject();
        JsonObject keys = new JsonObject()
                .put("content-type", 1)
                .put("extension", 1)
                .put("_id", 0);
        MongoDb.getInstance().find(DISCOVER_COLLECTION, query, sort, keys, event -> {
            if ("ok".equals(event.body().getString("status"))) {
                handler.handle(new Either.Right<>(event.body().getJsonArray("results")));
            } else {
                handler.handle(new Either.Left<>(event.body().getString("message")));
            }
        });
    }
}
