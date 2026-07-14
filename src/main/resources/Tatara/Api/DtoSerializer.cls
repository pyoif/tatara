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
        DEFINE VARIABLE hBuffer AS HANDLE     NO-UNDO.
        DEFINE VARIABLE oObj    AS JsonObject NO-UNDO.

        /* 1. Get the default buffer handle for the temp-table */
        hBuffer = phTable:DEFAULT-BUFFER-HANDLE.
        oObj = NEW JsonObject().

        /* 2. Ensure a record is currently scoped/active in the buffer */
        IF NOT hBuffer:AVAILABLE THEN
            hBuffer:FIND-FIRST() NO-ERROR.

        /* 3. Serialize the single row directly into the JsonObject */
        IF hBuffer:AVAILABLE THEN
            hBuffer:SERIALIZE-ROW("JSON", "JsonObject", oObj, FALSE, ?, FALSE, TRUE).

        RETURN oObj.
    END METHOD.

    /* ====================================================================
       1. ReadDatasetAsObject
       Converts a ProDataSet into a single, flat JSON Object with nested arrays.
       Ideal for: Details pages, single record fetches (e.g., /project/1)
       ==================================================================== */
    METHOD PUBLIC STATIC JsonObject ReadDatasetAsObject(INPUT phDataset AS HANDLE):
        DEFINE VARIABLE oTmpObj   AS JsonObject NO-UNDO.
        DEFINE VARIABLE oArr      AS JsonArray  NO-UNDO.
        DEFINE VARIABLE oResult   AS JsonObject NO-UNDO.
        DEFINE VARIABLE cTopTable AS CHARACTER  NO-UNDO.

        /* Handle validation safety */
        IF NOT VALID-HANDLE(phDataset) OR phDataset:TYPE <> "DATASET" THEN 
            RETURN NEW JsonObject().

        /* Dynamically find the root table name (checks SERIALIZE-NAME first, then falls back to default NAME) */
        cTopTable = phDataset:GET-BUFFER-HANDLE(1):SERIALIZE-NAME.
        IF cTopTable = "" OR cTopTable = ? THEN 
            cTopTable = phDataset:GET-BUFFER-HANDLE(1):NAME.

        oTmpObj = NEW JsonObject().

        /* Stream dataset natively, omitting the Dataset wrapper name */
        phDataset:WRITE-JSON("JsonObject", oTmpObj, FALSE, ?, FALSE, TRUE).

        /* Extract the single parent record out of its outer table array wrapper */
        IF oTmpObj:Has(cTopTable) THEN DO:
            oArr = oTmpObj:GetJsonArray(cTopTable).
            IF oArr:LENGTH > 0 THEN
                oResult = oArr:GetJsonObject(1). /* Grabs the record + nested children */
        END.

        /* Fallback safety to prevent returning an uninitialized object */
        IF NOT VALID-OBJECT(oResult) THEN
            oResult = NEW JsonObject().

        RETURN oResult.
    END METHOD.


    /* ====================================================================
       2. ReadDatasetAsArray
       Converts a ProDataSet root level into a clean JSON Array of records.
       Ideal for: Multi-record responses, lists, or paginated datasets
       ==================================================================== */
    METHOD PUBLIC STATIC JsonArray ReadDatasetAsArray(INPUT phDataset AS HANDLE):
        DEFINE VARIABLE oTmpObj   AS JsonObject NO-UNDO.
        DEFINE VARIABLE oResult   AS JsonArray  NO-UNDO.
        DEFINE VARIABLE cTopTable AS CHARACTER  NO-UNDO.

        /* Handle validation safety */
        IF NOT VALID-HANDLE(phDataset) OR phDataset:TYPE <> "DATASET" THEN 
            RETURN NEW JsonArray().

        /* Dynamically identify the root table name */
        cTopTable = phDataset:GET-BUFFER-HANDLE(1):SERIALIZE-NAME.
        IF cTopTable = "" OR cTopTable = ? THEN 
            cTopTable = phDataset:GET-BUFFER-HANDLE(1):NAME.

        oTmpObj = NEW JsonObject().

        /* Stream the dataset structure natively */
        phDataset:WRITE-JSON("JsonObject", oTmpObj, FALSE, ?, FALSE, TRUE).

        /* Pull the clean root array directly out of the JSON temporary envelope */
        IF oTmpObj:Has(cTopTable) THEN
            oResult = oTmpObj:GetJsonArray(cTopTable).

        /* Fallback safety to prevent returning an uninitialized array */
        IF NOT VALID-OBJECT(oResult) THEN
            oResult = NEW JsonArray().

        RETURN oResult.
    END METHOD.
END CLASS.
