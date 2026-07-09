USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.DtoSerializer:

    METHOD PUBLIC STATIC JsonArray ReadTempTableAsArray(INPUT phTable AS HANDLE):
        DEFINE VARIABLE oArr AS JsonArray NO-UNDO.
        oArr = NEW JsonArray().
        oArr:Read(phTable).
        RETURN oArr.
    END METHOD.

    METHOD PUBLIC STATIC JsonObject ReadTempTableAsObject(INPUT phTable AS HANDLE):
        DEFINE VARIABLE oObj AS JsonObject NO-UNDO.
        oObj = NEW JsonObject().
        oObj:Read(phTable).
        RETURN oObj.
    END METHOD.

END CLASS.
