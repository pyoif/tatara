/* app/common/ServiceFactory.cls */
CLASS Tatara.Api.ServiceFactory:

    METHOD PUBLIC STATIC Progress.Lang.Object GetService(INPUT cServiceName AS CHARACTER):
        CASE cServiceName:
            WHEN "FiltersService" THEN RETURN NEW services.FiltersService(
                CAST(Tatara.Api.ServiceFactory:GetHoldingRepository("FiltersRepository"), repositories.holding.IFiltersRepository)
            ).
            WHEN "BudgetService" THEN RETURN NEW services.BudgetService(
                CAST(Tatara.Api.ServiceFactory:GetProjectRepository("BudgetRepository"), repositories.project.IBudgetRepository)
            ).
            WHEN "ProjectService" THEN RETURN NEW services.ProjectService(
                CAST(Tatara.Api.ServiceFactory:GetProjectRepository("ProjectRepository"), repositories.project.IProjectRepository)
            ).
            WHEN "ContractService" THEN RETURN NEW services.ContractService(
                CAST(Tatara.Api.ServiceFactory:GetProjectRepository("ContractRepository"), repositories.project.IContractRepository)
            ).
            OTHERWISE RETURN ? .
        END CASE.
    END METHOD.


    /* Holding database repositories */
    METHOD PUBLIC STATIC Progress.Lang.Object GetHoldingRepository(INPUT cRepoName AS CHARACTER):
        CASE cRepoName:
            WHEN "FiltersRepository" THEN RETURN NEW repositories.holding.FiltersRepository().
            OTHERWISE RETURN ? .
        END CASE.
    END METHOD.


    /* Project database repositories */
    METHOD PUBLIC STATIC Progress.Lang.Object GetProjectRepository(INPUT cRepoName AS CHARACTER):
        CASE cRepoName:
            WHEN "BudgetRepository" THEN RETURN NEW repositories.project.BudgetRepository().
            WHEN "ProjectRepository" THEN RETURN NEW repositories.project.ProjectRepository().
            WHEN "ContractRepository" THEN RETURN NEW repositories.project.ContractRepository().
            OTHERWISE RETURN ? .
        END CASE.
    END METHOD.


END CLASS.
