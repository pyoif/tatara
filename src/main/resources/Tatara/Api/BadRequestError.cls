CLASS Tatara.Api.BadRequestError INHERITS Tatara.Api.ApiError:

    CONSTRUCTOR PUBLIC BadRequestError(INPUT pcMessage AS CHARACTER):
        SUPER(400, pcMessage).
    END CONSTRUCTOR.

END CLASS.
