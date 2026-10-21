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
  %PY% -m pip install pillow
)

%PY% controle_pc.py
if errorlevel 1 pause
