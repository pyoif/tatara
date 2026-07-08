CLASS Tatara.Api.ForbiddenError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC ForbiddenError(INPUT pcMessage AS CHARACTER):
        SUPER(403, pcMessage).
    END CONSTRUCTOR.

END CLASS.
