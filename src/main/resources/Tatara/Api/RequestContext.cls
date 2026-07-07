/* common/RequestContext.cls */
CLASS Tatara.Api.RequestContext:

    DEFINE PUBLIC PROPERTY PathParams AS Progress.Json.ObjectModel.JsonObject NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY Body AS LONGCHAR NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY QueryParams AS Progress.Json.ObjectModel.JsonObject NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY HttpMethod AS CHARACTER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC RequestContext(
        INPUT poPathParams AS Progress.Json.ObjectModel.JsonObject,
        INPUT pcBody AS LONGCHAR,
        INPUT poQueryParams AS Progress.Json.ObjectModel.JsonObject,
        INPUT pcHttpMethod AS CHARACTER):

        PathParams = poPathParams.
        Body = pcBody.
        QueryParams = poQueryParams.
        HttpMethod = pcHttpMethod.

    END CONSTRUCTOR.

    METHOD PUBLIC CHARACTER GetParam(INPUT pcName AS CHARACTER):
        DEFINE VARIABLE cValue AS CHARACTER NO-UNDO INITIAL "".

        IF VALID-OBJECT(PathParams) AND PathParams:Has(pcName) THEN DO:
            cValue = PathParams:GetCharacter(pcName).
            cValue = OpenEdge.Net.URI:Decode(cValue).
        END.

        RETURN cValue.
    END METHOD.

    METHOD PUBLIC CHARACTER GetQueryParam(INPUT pcName AS CHARACTER):
        DEFINE VARIABLE cValue AS CHARACTER NO-UNDO INITIAL "".

        IF VALID-OBJECT(QueryParams) AND QueryParams:Has(pcName) THEN DO:
            cValue = QueryParams:GetCharacter(pcName).
        END.

        RETURN cValue.
    END METHOD.

END CLASS.
