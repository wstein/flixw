@echo off
rem flixw cmd.exe trampoline -- invariant file.  Finds an initial java, prefers the
rem compiled stage 0 in the user cache, else launches the source.
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "SRC=%ROOT%.flix-wrapper\flix.java"

rem The cache is resolved first: a JDK flixw installed earlier lives in it, and is
rem the last thing worth trying when nothing else answers.
if defined FLIX_CACHE_HOME ( set "CACHE=%FLIX_CACHE_HOME%" ) else (
  set "CACHE=%LOCALAPPDATA%\flixw" )

rem CHOSEN marks an explicitly named JDK: those are obeyed as given, failing
rem included, rather than replaced by one the caller did not ask for.
set "CHOSEN=1"
if defined FLIX_JAVA_HOME ( set "JAVA0=%FLIX_JAVA_HOME%\bin\java.exe" ) else (
if defined JAVA_HOME ( set "JAVA0=%JAVA_HOME%\bin\java.exe" ) else (
set "CHOSEN="
for %%I in (java.exe) do set "JAVA0=%%~$PATH:I" ) )
rem Its path is read from a file rather than guessed: vendors nest differently.
rem It names something this script will execute, so it may only name something
rem inside the directory flixw unpacks into.
rem The marker is cache-controlled text naming something this script will execute,
rem so it is never echoed, called, or otherwise handed back to the parser: cmd
rem metacharacters in it would run before anything could validate the path. The
rem containment test uses delayed expansion alone -- strip the expected prefix,
rem then require the original to be exactly prefix plus remainder, which is a
rem starts-with test that never re-parses the value.
set "MINE="
if exist "%CACHE%\jdks\default" (
  for /f "usebackq delims=" %%J in ("%CACHE%\jdks\default") do (
    if not defined MINE set "MINE=%%J" ) )
if defined MINE (
  set "TAIL=!MINE:%CACHE%\jdks\=!"
  if not "!MINE!"=="%CACHE%\jdks\!TAIL!" set "MINE="
)
if defined MINE if not exist "!MINE!" set "MINE="
if not defined JAVA0 if defined MINE set "JAVA0=!MINE!"
if not defined JAVA0 (
  echo FLIXW003: no java executable found. Flix needs Java 21+. 1>&2
  echo           Install a JDK -- Eclipse Temurin is the usual choice: 1>&2
  echo             winget install EclipseAdoptium.Temurin.21.JDK 1>&2
  echo             https://adoptium.net/temurin/releases/?version=21 1>&2
  echo           Then set JAVA_HOME, or put its bin directory on PATH. 1>&2
  echo           With any Java 21+ present, flix.cmd --wrapper-install-jdk will 1>&2
  echo           fetch and verify one into the flixw cache for this project. 1>&2
  exit /b 127 )
if not exist "%JAVA0%" (
  echo FLIXW003: %JAVA0% not found. 1>&2
  exit /b 127 )
if not exist "%SRC%" (
  echo FLIXW009: missing %SRC% 1>&2
  exit /b 88 )

rem Feature version of the selected java, from the release file of its own JDK.
rem Only used to decide whether the compiled class is loadable: a JVM below the
rem floor cannot load it and exec leaves no way back.  Unknown changes nothing.
set "JHOME=%JAVA0:\bin\java.exe=%"
set "JFEATURE="
if exist "%JHOME%\release" (
  for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "%JHOME%\release" 2^>nul') do (
    for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) )
rem Unknown is not good enough: a java that is a shim script rather than a JDK
rem layout has no release file, and running the class blind fails on class file
rem version with no way back.  Default to the source path; earn the fast one.
rem A java below the floor is worse than none: it cannot load the compiled class
rem and, far enough below, cannot compile stage 0 either. Prefer a recorded JDK --
rem but never over an explicitly named one, which must fail loudly instead.
if not defined CHOSEN if defined JFEATURE if !JFEATURE! LSS 21 if defined MINE (
  set "JAVA0=!MINE!"
  set "JFEATURE="
  for %%H in ("!MINE!") do set "JHOME=%%~dpH"
  if exist "!JHOME!..\release" (
    for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "!JHOME!..\release" 2^>nul') do (
      for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) ) )
set "SLOWPATH=1"
if defined JFEATURE if !JFEATURE! GEQ 21 set "SLOWPATH="

set "H="
for /f "skip=1 delims=" %%L in ('certutil -hashfile "%SRC%" SHA256 2^>nul') do (
  if not defined H set "H=%%L" )
if defined H set "H=!H: =!"
if not defined SLOWPATH if defined H if exist "!CACHE!\stage0\!H!\flix.class" (
  set "FLIXW_SOURCE=%SRC%"
  "%JAVA0%" -cp "!CACHE!\stage0\!H!" flix %*
  exit /b !ERRORLEVEL! )
"%JAVA0%" "%SRC%" %*
exit /b !ERRORLEVEL!
