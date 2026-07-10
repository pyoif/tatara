USING Progress.Lang.*.

BLOCK-LEVEL ON ERROR UNDO, THROW.

CLASS Tatara.Api.QueryHelper:

    METHOD PUBLIC STATIC CHARACTER GetQueryValue(
        INPUT pcQuery AS CHARACTER,
        INPUT pcKey   AS CHARACTER):
        DEFINE VARIABLE cKey   AS CHARACTER NO-UNDO.
        DEFINE VARIABLE iStart AS INTEGER   NO-UNDO.
        DEFINE VARIABLE iAmp   AS INTEGER   NO-UNDO.
        cKey = pcKey + "=".
        iStart = INDEX(pcQuery, cKey) + LENGTH(cKey).
        IF iStart <= LENGTH(cKey) THEN RETURN ?.
        iAmp = INDEX(pcQuery, "&", iStart).
        IF iAmp = 0 THEN iAmp = LENGTH(pcQuery) + 1.
        RETURN OpenEdge.Net.URI:Decode(SUBSTRING(pcQuery, iStart, iAmp - iStart)).
    END METHOD.

END CLASS.
