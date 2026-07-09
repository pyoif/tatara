USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.
USING OpenEdge.Web.WebResponseWriter.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.ResponseWriter:

    METHOD PUBLIC STATIC VOID WriteJsonObject(
        INPUT poResponse AS OpenEdge.Web.WebResponse,
        INPUT poJson     AS Progress.Json.ObjectModel.JsonObject):

        DEFINE VARIABLE oWriter    AS WebResponseWriter               NO-UNDO.
        DEFINE VARIABLE lcJson    AS LONGCHAR NO-UNDO.

        poResponse:StatusCode    = 200.
        poResponse:ContentType   = "application/json".

        oWriter    = NEW WebResponseWriter(poResponse).
        oWriter:Open().
        poJson:Write(lcJson).
        oWriter:Write(lcJson).
        oWriter:Close().
    END METHOD.

    METHOD PUBLIC STATIC VOID WriteError(
        INPUT poResponse     AS OpenEdge.Web.WebResponse,
        INPUT piStatusCode   AS INTEGER,
        INPUT pcErrorMessage AS CHARACTER):

        DEFINE VARIABLE oWriter    AS WebResponseWriter NO-UNDO.
        DEFINE VARIABLE oJson      AS Progress.Json.ObjectModel.JsonObject NO-UNDO.

        poResponse:StatusCode = piStatusCode.

        oJson = NEW JsonObject().
        oJson:Add("error", pcErrorMessage).

        oWriter    = NEW WebResponseWriter(poResponse).
        oWriter:Open().
        oWriter:Write(oJson:GetJsonText()).
        oWriter:Close().
    END METHOD.

END CLASS.
