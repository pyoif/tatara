/* app/common/ErrorResponse.cls */
USING Tatara.Api.JsonHelper.

CLASS Tatara.Api.ErrorResponse:
    
    DEFINE PUBLIC PROPERTY HttpCode AS INTEGER NO-UNDO INIT 500 GET. SET.
    DEFINE PUBLIC PROPERTY ErrorCode AS CHARACTER NO-UNDO INIT "INTERNAL_ERROR" GET. SET.
    DEFINE PUBLIC PROPERTY Message AS CHARACTER NO-UNDO INIT "An error occurred" GET. SET.
    
    CONSTRUCTOR PUBLIC ErrorResponse(INPUT pHttpCode AS INTEGER, INPUT pCode AS CHARACTER, INPUT pMessage AS CHARACTER):
        ASSIGN
            THIS-OBJECT:HttpCode  = pHttpCode
            THIS-OBJECT:ErrorCode = pCode
            THIS-OBJECT:Message   = pMessage.
    END CONSTRUCTOR.
    
    METHOD PUBLIC CHARACTER ToJson():
        RETURN JsonHelper:CreateErrorResponse(THIS-OBJECT:ErrorCode, THIS-OBJECT:Message).
    END METHOD.

END CLASS.
