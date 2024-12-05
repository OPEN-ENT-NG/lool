package fr.openent.lool.controller;

import fr.openent.lool.bean.Token;
import fr.openent.lool.core.constants.Field;
import fr.openent.lool.helper.DateHelper;
import fr.openent.lool.helper.TraceHelper;
import fr.openent.lool.provider.Wopi;
import fr.openent.lool.provider.WopisProviders;
import fr.openent.lool.service.DocumentService;
import fr.openent.lool.service.FileService;
import fr.openent.lool.service.Impl.DefaultDocumentService;
import fr.openent.lool.service.Impl.DefaultFileService;
import fr.openent.lool.utils.Actions;
import fr.openent.lool.utils.Bindings;
import fr.openent.lool.utils.Headers;
import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.common.controller.ControllerHelper;
import org.entcore.common.storage.Storage;
import org.entcore.common.user.UserUtils;

import java.text.ParseException;

import static org.entcore.common.http.response.DefaultResponseHandler.defaultResponseHandler;

public class WopiController extends ControllerHelper {

    private final DocumentService documentService;
    private final FileService fileService;

    public WopiController(EventBus eb, Storage storage) {
        super();
        documentService = new DefaultDocumentService(eb, storage);
        fileService = new DefaultFileService(storage);
    }

    @Get("/wopi/files/:id")
    public void checkFileInfo(HttpServerRequest request) {
        String loolToken = request.params().get("access_token");
        String documentId = request.params().get(Field.ID);
        final Wopi wopiService = WopisProviders.getProvider(Renders.getHost(request));
        wopiService.helper().validateToken(loolToken, documentId, Bindings.READ.toString(), validationObject -> {
            if (Boolean.FALSE.equals(validationObject.getBoolean("valid"))) {
                unauthorized(request);
                return;
            }

            Token token = new Token(validationObject.getJsonObject(Field.TOKEN));
            wopiService.helper().userCanWrite(token.getSessionId(), token.getDocument(), canWrite ->
                documentService.get(request.getParam(Field.ID), event -> {
                    if (event.isRight()) {
                        JsonObject document = event.right().getValue();
                        JsonObject metadata = document.getJsonObject(Field.METADATA);
                        // Create wopi response config
                        JsonObject response = new JsonObject()
                                .put(Field.BASEFILENAME, document.getString(Field.NAME))
                                .put(Field.SIZE, metadata.getInteger(Field.size))
                                .put(Field.OWNERID, document.getString(Field.OWNER))
                                .put(Field.USERID, token.getUser())
                                .put(Field.USERFRIENDLYNAME, token.getDisplayName())
                                .put(Field.VERSION, DateHelper.getDateString(document.getString(Field.MODIFIED), DateHelper.MONGO_DATE_FORMAT, DateHelper.ISO8601_FORMAT))
                                .put(Field.LASTMODIFIEDTIME, DateHelper.getDateString(document.getString(Field.MODIFIED), DateHelper.MONGO_DATE_FORMAT, DateHelper.ISO8601_FORMAT))
                                .put(Field.USERCANWRITE, canWrite);
                        // Merge server capabilities into wopi response config
                        response.mergeIn(new JsonObject(wopiService.config().serverCapabilities()));
                        renderJson(request, response);
                    } else {
                        badRequest(request);
                    }
                })
            );
        });
    }

    @Get("/wopi/files/:id/contents")
    public void getFile(HttpServerRequest request) {
        String documentId = request.getParam(Field.ID);
        final Wopi wopiService = WopisProviders.getProvider(Renders.getHost(request));
        wopiService.helper().validateToken(request.getParam("access_token"), documentId, Bindings.READ.toString(), validation -> {
            if (Boolean.FALSE.equals(validation.getBoolean("valid"))) {
                unauthorized(request);
                return;
            }

            documentService.get(documentId, event -> {
                if (event.isRight()) {
                    JsonObject document = event.right().getValue();
                    fileService.get(document.getString(Field.FILE), buffer ->
                            request.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/octet-stream")
                                    .putHeader("Content-Transfer-Encoding", "Binary")
                                    .putHeader("Content-disposition", "attachment; filename=" + document.getString(Field.NAME))
                                    .end(buffer));
                } else {
                    badRequest(request);
                }
            });
        });
    }

    @Post("/wopi/files/:id/contents")
    public void putFile(HttpServerRequest request) {
        request.pause();
        boolean isAutoSave = Boolean.parseBoolean(request.getHeader(Headers.AUTO_SAVE.toString()));
        boolean isExitSave = request.headers().contains(Headers.EXIT_SAVE.toString()) && Boolean.parseBoolean(request.headers().get(Headers.EXIT_SAVE.toString()));
        final Wopi wopiService = WopisProviders.getProvider(Renders.getHost(request));
        wopiService.helper().validateToken(request.params().get("access_token"), request.params().get(Field.ID), Bindings.CONTRIB.toString(), validation -> {
            if (Boolean.FALSE.equals(validation.getBoolean("valid")) && !isExitSave) {
                unauthorized(request);
                return;
            }
            Token token = new Token(validation.getJsonObject(Field.TOKEN));

            documentService.get(request.getParam(Field.ID), event -> {
                if (event.isRight()) {
                    JsonObject document = event.right().getValue();
                    JsonObject metadata = document.getJsonObject(Field.METADATA);
                    request.resume();
                    fileService.add(request, metadata.getString("content-type"), document.getString(Field.NAME), storageEvent -> {
                        if (storageEvent.isLeft()) {
                            log.error(storageEvent.left().getValue());
                            renderError(request);
                            return;
                        }
                        JsonObject storageBody = storageEvent.right().getValue();
                        Handler<Either<String, JsonObject>> updateHandler = updateEvent -> {
                            if (updateEvent.isRight()) {
                                request.response().setStatusCode(200).end();
                            } else {
                                renderError(request);
                            }
                        };

                        if (isAutoSave || isExitSave) {
                            documentService.updateRevisionId(request.getParam(Field.ID), storageBody.getString(Field._ID), updateHandler);
                            if (isExitSave) {
                                wopiService.helper().deleteToken(token.getId(), either -> {
                                    if (either.isLeft()) {
                                        log.error("Failed to delete token on exit save");
                                    }
                                });
                            }
                        } else {
                            documentService.update(request.getParam(Field.ID), storageBody.getString(Field._ID), storageBody.getJsonObject(Field.METADATA), updateHandler);
                            TraceHelper.add(Actions.NEW_VERSION.name(), token.getUser(), token.getDocument(), TraceHelper.getFileExtension(document.getString(Field.NAME)));
                        }
                    });
                } else {
                    renderError(request);
                }
            });
        });
    }

    @Delete("/wopi/documents/:id/tokens/:token")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void invalidateToken(HttpServerRequest request) {
        final Wopi wopiService = WopisProviders.getProvider(Renders.getHost(request));
        if (Boolean.FALSE.equals(wopiService.provider().revokeToken())) {
            ok(request);
            return;
        }

        String documentId = request.getParam(Field.ID);
        String accessToken = request.getParam(Field.TOKEN);
        UserUtils.getUserInfos(eb, request, user -> wopiService.helper().isUserToken(user.getUserId(), accessToken, documentId, isUserToken -> {
            if (Boolean.TRUE.equals(isUserToken)) {
                wopiService.helper().invalidateToken(accessToken, defaultResponseHandler(request));
            } else {
                unauthorized(request);
            }
        }));
    }

    @Post("/wopi/documents/:id/tokens/:token")
    @ApiDoc("Delete token based on beacon api")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void deleteTokenWithBeacon(HttpServerRequest request) {
        invalidateToken(request);
    }
}
