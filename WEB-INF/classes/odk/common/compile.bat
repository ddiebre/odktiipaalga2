@echo off
REM Compile les classes utilitaires partagees (resolution du schema ODK, import
REM de formulaires XML) - utilisees par EditSubmissionServlet, ProjectServlet et HomeServlet
set TOMCAT_HOME=c:\xampp\tomcat
set SERVLET_JAR=%TOMCAT_HOME%\lib\servlet-api.jar
set LIB_DIR=%~dp0..\..\..\lib

if not exist "%SERVLET_JAR%" (
    echo Erreur: servlet-api.jar introuvable. Ajustez TOMCAT_HOME dans ce script.
    exit /b 1
)

cd /d "%~dp0"
javac -encoding UTF-8 -cp "%SERVLET_JAR%;%LIB_DIR%\*" FormSchemaUtil.java FormUploadUtil.java
if %ERRORLEVEL% equ 0 (
    echo Compilation reussie.
) else (
    echo Echec de la compilation.
    exit /b 1
)
