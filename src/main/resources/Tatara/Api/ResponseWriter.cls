USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.
USING OpenEdge.Web.WebResponseWriter.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.ResponseWriter:

    METHOD PUBLIC STATIC VOID WriteJsonObject(
        INPUT poResponse AS OpenEdge.Web.WebResponse,
        INPUT poJson     AS Progress.Json.ObjectModel.JsonObject):

        DEFINE VARIABLE oWriter    AS OpenEdge.Web.WebResponseWriter NO-UNDO.
        DEFINE VARIABLE oMemStream AS MemoryOutputStream              NO-UNDO.
        DEFINE VARIABLE mPayload   AS MEMPTR                          NO-UNDO.

        poResponse:StatusCode    = 200.
        poResponse:ContentType   = "application/json".

        oWriter    = NEW OpenEdge.Web.WebResponseWriter(poResponse).
        oMemStream = NEW MemoryOutputStream().
        oWriter:Open().
        poJson:Write(oMemStream).
        mPayload = oMemStream:Data.
        oWriter:Write(mPayload).
        oWriter:Close().
        SET-SIZE(mPayload) = 0.
        DELETE OBJECT oMemStream.
    END METHOD.

    METHOD PUBLIC STATIC VOID WriteError(
        INPUT poResponse     AS OpenEdge.Web.WebResponse,
        INPUT piStatusCode   AS INTEGER,
        INPUT pcErrorMessage AS CHARACTER):

        DEFINE VARIABLE oWriter    AS OpenEdge.Web.WebResponseWriter NO-UNDO.
        DEFINE VARIABLE oMemStream AS MemoryOutputStream              NO-UNDO.
        DEFINE VARIABLE mPayload   AS MEMPTR                          NO-UNDO.
        DEFINE VARIABLE oJson      AS JsonObject                       NO-UNDO.

        poResponse:StatusCode = piStatusCode.

        oJson = NEW JsonObject().
        oJson:Add("error", pcErrorMessage).

        oWriter    = NEW OpenEdge.Web.WebResponseWriter(poResponse).
        oMemStream = NEW MemoryOutputStream().
        oWriter:Open().
        oJson:Write(oMemStream).
        mPayload = oMemStream:Data.
        oWriter:Write(mPayload).
        oWriter:Close().
        SET-SIZE(mPayload) = 0.
        DELETE OBJECT oMemStream.
    END METHOD.

END CLASS.
