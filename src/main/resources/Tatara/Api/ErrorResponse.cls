CLASS Tatara.Api.ErrorResponse:

    DEFINE PUBLIC PROPERTY errorCode    AS CHARACTER NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY errorMessage AS CHARACTER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC ErrorResponse(INPUT pcMessage AS CHARACTER):
        errorCode = "".
        errorMessage = pcMessage.
    END CONSTRUCTOR.

END CLASS.
