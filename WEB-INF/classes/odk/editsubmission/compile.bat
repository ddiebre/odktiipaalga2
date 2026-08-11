@echo off
REM Compile le servlet /editSubmission et son filtre associe
REM Adapter TOMCAT_HOME si Tomcat est ailleurs
set TOMCAT_HOME=c:\xampp\tomcat
set SERVLET_JAR=%TOMCAT_HOME%\lib\servlet-api.jar
set LIB_DIR=%~dp0..\..\..\lib
set CLASSES_DIR=%~dp0..\..\..\classes

if not exist "%SERVLET_JAR%" (
    echo Erreur: servlet-api.jar introuvable. Ajustez TOMCAT_HOME dans ce script.
    exit /b 1
)

call "%CLASSES_DIR%\odk\common\compile.bat"
if %ERRORLEVEL% neq 0 exit /b 1

cd /d "%~dp0"
javac -encoding UTF-8 -cp "%SERVLET_JAR%;%LIB_DIR%\*;%CLASSES_DIR%" EditSubmissionServlet.java EditSubmissionMenuLinkFilter.java
if %ERRORLEVEL% equ 0 (
    echo Compilation reussie. Redemarrez Tomcat puis testez https://odktiipaalga.ngrok.app/ODKTiipaalga2/editSubmission
) else (
    echo Echec de la compilation.
    exit /b 1
)
