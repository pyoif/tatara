CLASS Tatara.Api.UnauthorizedError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC UnauthorizedError(INPUT pcMessage AS CHARACTER):
        SUPER(401, pcMessage).
    END CONSTRUCTOR.

END CLASS.
