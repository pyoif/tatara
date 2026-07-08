CLASS Tatara.Api.ConflictError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC ConflictError(INPUT pcMessage AS CHARACTER):
        SUPER(409, pcMessage).
    END CONSTRUCTOR.

END CLASS.
