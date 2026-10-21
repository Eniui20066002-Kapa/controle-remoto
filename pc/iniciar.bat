@echo off
cd /d "%~dp0"
title Controle Remoto do Celular

set PY=python
%PY% --version >nul 2>nul
if errorlevel 1 set PY=py
%PY% --version >nul 2>nul
if errorlevel 1 (
  echo Python nao foi encontrado. Instale em python.org e marque "Add Python to PATH".
  pause
  exit /b 1
)

%PY% -c "import PIL" >nul 2>nul
if errorlevel 1 (
  echo Instalando a biblioteca Pillow, so na primeira vez...
  if exist wheels (
    %PY% -m pip install --no-index --find-links wheels pillow
  ) else (
    %PY% -m pip install pillow
  )
)

%PY% -c "import PIL" >nul 2>nul
if errorlevel 1 (
  echo.
  echo Nao consegui instalar o Pillow.
  echo Se o PC estiver sem internet, copie a pasta "wheels" para junto deste arquivo e abra de novo.
  pause
  exit /b 1
)

%PY% controle_pc.py
if errorlevel 1 pause
