/* common/ResponseContext.cls */
CLASS Tatara.Api.ResponseContext:

    DEFINE PUBLIC PROPERTY HttpCode    AS INTEGER   NO-UNDO INITIAL 200                GET. SET.
    DEFINE PUBLIC PROPERTY Body        AS LONGCHAR  NO-UNDO INITIAL ""                 GET. SET.
    DEFINE PUBLIC PROPERTY ContentType AS CHARACTER NO-UNDO INITIAL "application/json" GET. SET.

    CONSTRUCTOR PUBLIC ResponseContext():
        /* Properties initialized with INITIAL values */
    END CONSTRUCTOR.

END CLASS.
