/* app/common/JsonHelper.cls */
USING Progress.Json.ObjectModel.JsonObject.
USING Progress.Json.ObjectModel.JsonArray.
USING Progress.Json.ObjectModel.ObjectModelParser.

CLASS Tatara.Api.JsonHelper:

    METHOD PUBLIC STATIC JsonArray TempTableToJson(INPUT TABLE-HANDLE hTable):
        DEFINE VARIABLE lcJson    AS LONGCHAR          NO-UNDO.
        DEFINE VARIABLE oParser   AS ObjectModelParser  NO-UNDO.
        DEFINE VARIABLE oWrapper  AS JsonObject          NO-UNDO.
        DEFINE VARIABLE cNames    AS CHARACTER EXTENT    NO-UNDO. /* Changed from JsonArray */
        DEFINE VARIABLE cKey      AS CHARACTER           NO-UNDO.
        
        /* 1. Serialize the temp-table to a JSON string */
        hTable:WRITE-JSON("LONGCHAR", lcJson).
        
        /* 2. Parse and extract array from temp-table wrapper: {"tt-name": [...]} -> [...] */
        oParser = NEW ObjectModelParser().
        oWrapper = CAST(oParser:Parse(lcJson), JsonObject).
        
        cNames = oWrapper:GetNames().  /* Correctly receives the CHARACTER EXTENT */
        cKey = cNames[1].              /* Uses standard ABL array indexing */
        
        RETURN oWrapper:GetJsonArray(cKey).
    END METHOD.
    
    METHOD PUBLIC STATIC CHARACTER CreateSuccessResponse(INPUT poData AS Progress.Lang.Object):
        DEFINE VARIABLE oResponse AS JsonObject NO-UNDO.
        
        oResponse = NEW JsonObject().
        oResponse:Add("success", TRUE).
        
        IF TYPE-OF(poData, JsonArray) THEN
            oResponse:Add("data", CAST(poData, JsonArray)).
        ELSE IF TYPE-OF(poData, JsonObject) THEN
            oResponse:Add("data", CAST(poData, JsonObject)).
        
        RETURN STRING(oResponse:GetJsonText()).
    END METHOD.
    
    METHOD PUBLIC STATIC CHARACTER CreateErrorResponse(INPUT cCode AS CHARACTER, INPUT cMessage AS CHARACTER):
        DEFINE VARIABLE oResponse AS JsonObject NO-UNDO.
        DEFINE VARIABLE oError AS JsonObject NO-UNDO.
        
        oResponse = NEW JsonObject().
        oError = NEW JsonObject().
        oError:Add("code", cCode).
        oError:Add("message", cMessage).
        
        oResponse:Add("success", FALSE).
        oResponse:Add("error", oError).
        
        RETURN STRING(oResponse:GetJsonText()).
    END METHOD.

END CLASS.