USING Progress.Lang.*.
USING Progress.Json.ObjectModel.*.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.DtoSerializer:

    METHOD PUBLIC STATIC JsonObject ToJsonObject(
        INPUT poDto   AS Progress.Lang.Object,
        INPUT pcNames AS CHARACTER EXTENT,
        INPUT pcTypes AS CHARACTER EXTENT,
        INPUT plIsDto AS LOGICAL EXTENT):

        DEFINE VARIABLE oJson     AS JsonObject                       NO-UNDO.
        DEFINE VARIABLE oVal      AS Progress.Lang.Object             NO-UNDO.
        DEFINE VARIABLE i         AS INTEGER                          NO-UNDO.
        DEFINE VARIABLE cType     AS CHARACTER                        NO-UNDO.
        DEFINE VARIABLE cVal      AS CHARACTER                        NO-UNDO.
        DEFINE VARIABLE oPropInfo AS Progress.Lang.Class.Property     NO-UNDO.

        oJson = NEW JsonObject().

        IF NOT VALID-OBJECT(poDto) THEN RETURN oJson.

        DO i = 1 TO EXTENT(pcNames):
            ASSIGN
                cType = pcTypes[i]
                oVal  = ?
                .

            oPropInfo = ?.
            IF VALID-OBJECT(poDto:GetClass()) THEN DO:
                oPropInfo = poDto:GetClass():GetProperty(pcNames[i]) NO-ERROR.
                IF VALID-OBJECT(oPropInfo) THEN
                    oVal = oPropInfo:Get(poDto) NO-ERROR.
            END.

            IF plIsDto[i] THEN DO:
                IF VALID-OBJECT(oVal) THEN
                    oJson:Add(pcNames[i], ToJsonObject(oVal, ?, ?, ?)).
                ELSE
                    oJson:AddNull(pcNames[i]).
            END.
            ELSE DO:
                IF oVal = ? THEN
                    oJson:AddNull(pcNames[i]).
                ELSE DO:
                    CASE cType:
                        WHEN "INTEGER" OR WHEN "INT64" THEN
                            oJson:Add(pcNames[i], INTEGER(oVal)).
                        WHEN "DECIMAL" THEN
                            oJson:Add(pcNames[i], DECIMAL(oVal)).
                        WHEN "LOGICAL" THEN
                            oJson:Add(pcNames[i], LOGICAL(oVal)).
                        WHEN "DATE" THEN
                            oJson:Add(pcNames[i], DATE(oVal)).
                        WHEN "DATETIME" OR WHEN "DATETIME-TZ" THEN
                            oJson:Add(pcNames[i], DATETIME(oVal)).
                        WHEN "CHARACTER" OR WHEN "LONGCHAR" THEN DO:
                            cVal = STRING(oVal).
                            oJson:Add(pcNames[i], cVal).
                        END.
                        OTHERWISE
                            oJson:AddNull(pcNames[i]).
                    END CASE.
                END.
            END.
        END.

        RETURN oJson.
    END METHOD.

END CLASS.
