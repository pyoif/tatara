/* app/common/RequestContextBuilder.cls */
USING OpenEdge.Web.IWebRequest.

CLASS Tatara.Api.RequestContextBuilder:

    METHOD PUBLIC STATIC Tatara.Api.RequestContext FromWebRequest(
        INPUT poRequest AS IWebRequest):
        DEFINE VARIABLE cEmpty AS CHARACTER EXTENT NO-UNDO.
        RETURN FromWebRequest(poRequest, cEmpty).
    END METHOD.

    METHOD PUBLIC STATIC Tatara.Api.RequestContext FromWebRequest(
        INPUT poRequest    AS IWebRequest,
        INPUT pcParamNames AS CHARACTER EXTENT):

        DEFINE VARIABLE oPathParams  AS Progress.Json.ObjectModel.JsonObject NO-UNDO.
        DEFINE VARIABLE oQueryParams AS Progress.Json.ObjectModel.JsonObject NO-UNDO.
        DEFINE VARIABLE cBody        AS LONGCHAR  NO-UNDO.
        DEFINE VARIABLE cQuery       AS CHARACTER NO-UNDO.
        DEFINE VARIABLE cPair        AS CHARACTER NO-UNDO.
        DEFINE VARIABLE cKey         AS CHARACTER NO-UNDO.
        DEFINE VARIABLE cValue       AS CHARACTER NO-UNDO.
        DEFINE VARIABLE iPos         AS INTEGER   NO-UNDO.
        DEFINE VARIABLE ix           AS INTEGER   NO-UNDO.
        DEFINE VARIABLE cParamName   AS CHARACTER NO-UNDO.
        DEFINE VARIABLE cParamValue  AS CHARACTER NO-UNDO.

        oPathParams  = NEW Progress.Json.ObjectModel.JsonObject().
        oQueryParams = NEW Progress.Json.ObjectModel.JsonObject().

        /* Path parameters — extracted from IWebRequest via the param names
           declared in the route's annotation ({name} placeholders). */
        DO ix = 1 TO EXTENT(pcParamNames):
            cParamName = pcParamNames[ix].
            IF cParamName <> "" AND cParamName <> ? THEN DO:
                cParamValue = poRequest:GetPathParameter(cParamName).
                IF cParamValue <> ? THEN
                    oPathParams:Add(cParamName, cParamValue).
            END.
        END.

        IF VALID-OBJECT(poRequest:Entity) THEN
            cBody = CAST(poRequest:Entity, Progress.Lang.Object):ToString().

        cQuery = poRequest:URI:QueryString.
        IF cQuery <> "" AND cQuery <> ? THEN DO:

            IF cQuery BEGINS "?" THEN
                cQuery = SUBSTRING(cQuery, 2).

            DO ix = 1 TO NUM-ENTRIES(cQuery, '&'):
                cPair = ENTRY(ix, cQuery, '&').

                iPos = INDEX(cPair, "=").
                IF iPos > 0 THEN DO:
                    cKey = TRIM(SUBSTRING(cPair, 1, iPos - 1)).
                    cValue = TRIM(SUBSTRING(cPair, iPos + 1)).
                    cValue = OpenEdge.Net.URI:Decode(cValue).
                    oQueryParams:Add(cKey, cValue).
                END.
                ELSE DO:
                    oQueryParams:Add(TRIM(cPair), "").
                END.
            END.
        END.

        RETURN NEW Tatara.Api.RequestContext(
            oPathParams,
            cBody,
            oQueryParams,
            poRequest:Method
        ).
    END METHOD.

END CLASS.
