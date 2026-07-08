CLASS Tatara.Api.InternalError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC InternalError(INPUT pcMessage AS CHARACTER):
        SUPER(500, pcMessage).
    END CONSTRUCTOR.

END CLASS.
