/* common/ResponseWriter.cls */
USING OpenEdge.Web.IWebRequest.
USING OpenEdge.Web.WebResponse.
USING OpenEdge.Web.WebResponseWriter.

CLASS Tatara.Api.ResponseWriter:

    METHOD PUBLIC STATIC VOID Write(
        INPUT poRequest  AS OpenEdge.Web.IWebRequest,
        INPUT poResponse AS Tatara.Api.ResponseContext):

        DEFINE VARIABLE oWebResponse AS OpenEdge.Web.WebResponse        NO-UNDO.
        DEFINE VARIABLE oWriter      AS OpenEdge.Web.WebResponseWriter  NO-UNDO.
        DEFINE VARIABLE cBody        AS LONGCHAR NO-UNDO.
        DEFINE VARIABLE iOffset      AS INTEGER  NO-UNDO.
        DEFINE VARIABLE iChunkSize   AS INTEGER  NO-UNDO INITIAL 32000.

        oWebResponse = NEW OpenEdge.Web.WebResponse().
        oWebResponse:StatusCode  = poResponse:HttpCode.
        oWebResponse:ContentType = poResponse:ContentType.

        oWriter = NEW OpenEdge.Web.WebResponseWriter(oWebResponse).
        oWriter:Open().

        cBody = poResponse:Body.
        iOffset = 1.
        DO WHILE iOffset <= LENGTH(cBody):
            oWriter:Write(SUBSTRING(cBody, iOffset, iChunkSize)).
            iOffset = iOffset + iChunkSize.
        END.

        oWriter:Close().

    END METHOD.

END CLASS.
