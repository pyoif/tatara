CLASS Tatara.Api.ApiError INHERITS Progress.Lang.AppError:

    DEFINE PUBLIC PROPERTY HttpCode AS INTEGER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC ApiError(INPUT piCode AS INTEGER, INPUT pcMessage AS CHARACTER):
        SUPER(pcMessage).
        HttpCode = piCode.
    END CONSTRUCTOR.

END CLASS.
