CLASS Tatara.Api.ApiError INHERITS Progress.Lang.AppError:

    DEFINE PUBLIC PROPERTY HttpCode AS INTEGER   NO-UNDO GET. SET.
    DEFINE PUBLIC PROPERTY Message AS CHARACTER NO-UNDO GET. SET.

    CONSTRUCTOR PUBLIC ApiError(INPUT piCode AS INTEGER, INPUT pcMessage AS CHARACTER):
        SUPER().
        HttpCode = piCode.
        Message = pcMessage.
    END CONSTRUCTOR.

END CLASS.
