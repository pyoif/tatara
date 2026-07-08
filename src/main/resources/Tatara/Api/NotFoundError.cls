CLASS Tatara.Api.NotFoundError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC NotFoundError(INPUT pcMessage AS CHARACTER):
        SUPER(404, pcMessage).
    END CONSTRUCTOR.

END CLASS.
