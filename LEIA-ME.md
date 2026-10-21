# Controle Remoto do Celular (pela rede Wi-Fi / LAN)

Mostra a tela do celular Android numa janela no PC e deixa você controlar com o mouse e o teclado.
Feito para funcionar **sem root e sem cabo USB**, inclusive no **Android 6.0**.

O projeto tem duas partes:

- `app/` — o app do celular (o GitHub compila o APK para você).
- `pc/` — o programa do PC (Windows, em Python).

---

## Parte 1 — Gerar o APK (uma vez só)

1. Entre em **github.com**, clique no **+** (canto superior direito) → **New repository**.
   Nome: `controle-remoto` → deixe **Public** → **Create repository**.
2. Na página do repositório novo, clique em **uploading an existing file**.
3. Extraia este ZIP no PC e **arraste para a página TUDO que está dentro da pasta**
   (as pastas `app`, `pc`, `.github` e os arquivos soltos). Arraste as **pastas**, não só os arquivos.
4. Clique em **Commit changes**.
5. Abra a aba **Actions**. Se aparecer um botão verde pedindo para habilitar, clique nele.
   O trabalho **Compilar APK** começa sozinho e leva uns 5 a 8 minutos.
   Quando ficar com o ícone verde ✔, clique nele.
6. Role até o final da página, na seção **Artifacts**, e baixe **ControleRemoto-APK**.
   Vem um ZIP; extraia e você terá o arquivo `app-debug.apk`.

**Se a aba Actions estiver vazia** (a pasta `.github` não subiu): no repositório, clique em
**Add file → Create new file**, escreva no nome `.github/workflows/build.yml`, cole o conteúdo
do arquivo `.github/workflows/build.yml` deste ZIP e clique em **Commit changes**.

**Se a compilação ficar vermelha ✘**: abra o trabalho que falhou, copie as últimas linhas do log
e me mande que eu corrijo.

---

## Parte 2 — Instalar no celular

1. Mande o `app-debug.apk` para o celular com o seu programa de transferência de arquivos.
2. No celular: **Configurações → Segurança → Fontes desconhecidas** → ligar.
3. Abra o arquivo `app-debug.apk` (pelo app de Arquivos ou Downloads) e toque em **Instalar**.

---

## Parte 3 — Usar

**No celular** (app **Controle Remoto**):

1. Toque em **1) Ativar o controle (Acessibilidade)** → **Controle Remoto** → ligue → OK.
   Sem isso você só vê a tela, não controla.
2. Toque em **2) Iniciar compartilhamento** → **Começar agora**.
   O app mostra o **IP** e o **PIN** (o PIN muda toda vez que você inicia).

**No PC** (celular e PC no **mesmo Wi-Fi/roteador**):

1. Instale o Python em python.org se ainda não tiver (marque **Add Python to PATH**).
2. Abra a pasta `pc` e dê dois cliques em **`iniciar.bat`** (na primeira vez ele instala o Pillow).
3. Digite o IP e o PIN (ou clique em **Procurar na rede**) e clique em **Conectar**.

### Controles na janela do PC

| Ação | Como fazer |
|---|---|
| Tocar | Clique esquerdo |
| Toque longo | Segurar o clique esquerdo por mais de meio segundo |
| Deslizar / rolar | Arrastar com o mouse |
| Rolar | Roda do mouse |
| Voltar | Botão direito do mouse, tecla **Esc** ou botão **Voltar** |
| Tela inicial | Botão do meio do mouse ou botão **Início** |
| Digitar | Clique no campo no celular e digite no teclado do PC (ou use **Enviar texto**; **Ctrl+V** cola) |
| Tela do celular apagada | Botão **Acordar tela** |
| Imagem lenta ou travando | Mude a **Qualidade** para **Leve** |

---

## Limites no Android 6.0 sem root (importante)

O Android só libera toques em qualquer ponto da tela a partir do **7.0**. No 6.0, o controle funciona assim:

- **Toque**: funciona em botões, ícones, itens de lista e campos (tudo que o Android reconhece como "clicável").
  Não funciona em áreas desenhadas à mão, como **jogos**, mapas e alguns apps de câmera.
- **Deslizar**: vira **rolagem** de listas e páginas. Não faz gestos livres (zoom, desenhar, arrastar item).
- **Teclado**: o teclado do celular não aparece na imagem de forma útil. Digite pelo teclado do PC.
  A tecla **Enter** só funciona em campos de várias linhas; em barras de busca, toque no botão de buscar.
- Apps que **bloqueiam captura de tela** (bancos, alguns apps de streaming) aparecem **pretos**.
- Em celulares com Android 7.0 ou mais novo, toque e deslize reais funcionam normalmente.

## Segurança

- O app só aceita conexão de quem digitar o **PIN** que aparece no celular, e bloqueia por 1 minuto
  depois de 5 tentativas erradas.
- A conexão **não é criptografada**. Use só na sua rede de casa, nunca em Wi-Fi público.
- Quando terminar, toque em **Parar compartilhamento** no app. Para desligar o controle de vez,
  desative **Controle Remoto** em Configurações → Acessibilidade.
- Existe uma notificação fixa enquanto o compartilhamento está ativo, de propósito, para você saber que está ligado.

## Se não conectar

1. Os dois estão no **mesmo Wi-Fi**? (não vale celular no 4G)
2. O app mostra **Compartilhando a tela**? O IP digitado é o que aparece nele?
3. Alguns roteadores têm **Isolamento de AP / Isolamento de clientes** ligado, o que impede um aparelho de
   falar com outro. Desligue isso nas configurações do roteador.
4. O firewall do Windows pode perguntar se o Python pode acessar a rede: permita em **Rede privada**.
