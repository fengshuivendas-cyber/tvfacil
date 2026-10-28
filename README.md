# TV Fácil

App de TV ao vivo para **Android TV / Google TV (TCL)** feito para pessoas que só querem
**digitar o número do canal e assistir**, como na TV antiga. Sem menus, sem grade de programação,
sem poluição na tela: só o vídeo.

> O app é apenas um player. Ele não vem com canais; você cadastra a(s) lista(s) que contratar
> (use sempre listas de provedores autorizados).

---

## Como seus pais usam

| Faça isto no controle | O que acontece |
|---|---|
| Digitar o número (ex.: **1** e **2**) | Troca para o canal 12 (espera 2,5 s, ou aperte **OK** para trocar na hora) |
| **CH+** / **CH-** (ou seta ↑ / ↓) | Próximo / canal anterior |
| **0** sozinho | Volta ao canal que estavam vendo antes |
| **OK** | Mostra, por 4 segundos, o número e o nome do canal |
| **Voltar** duas vezes | Sai do app |

Na tela só aparece: o número que está sendo digitado (canto superior direito) e, ao trocar de canal,
o número e o nome (canto inferior esquerdo). Nada mais.

Ao abrir o app, ele já volta **no último canal** que estavam assistindo.

---

## Como você configura (uma vez)

1. Abra o app. Na primeira vez ele já abre a tela **Configurar listas de canais**.
   (Depois: **segure o botão OK** por 1 segundo, ou aperte **MENU**, e escolha *Configurar listas*.)
2. A tela mostra um endereço (ex.: `http://192.168.0.15:8080`) e um **código de 6 números**.
   No **celular ligado no mesmo Wi-Fi**, abra o navegador, digite o endereço e o código.
   *(Digitar link e senha com o controle é ruim; por isso existe o celular. Também dá para
   cadastrar direto na TV com o botão "+ Adicionar lista".)*
3. **Adicionar lista** – dois formatos, conforme o que o provedor entregar:
   - **Link M3U** – cole o link.
   - **Servidor, usuário e senha** (formato "Xtream") – preencha os três campos.
   Dá para cadastrar **várias listas** (ex.: principal e reserva) e escolher qual está **em uso**.
4. Aguarde baixar (a situação aparece no celular e na TV). Só entram **canais ao vivo**
   (filmes e séries são ignorados). A lista fica guardada na TV e é atualizada sozinha a cada 12 h.
5. **Meus canais** (o mais importante para seus pais): busque o canal pelo nome e dê o número que
   eles já conhecem (ex.: *Globo = 4*, *SBT = 5*…). Com isso:
   - o que eles digitam no controle é exatamente esse número;
   - **CH+/CH-** passam só pelos canais escolhidos (nada de 3.000 canais);
   - os outros canais continuam acessíveis a partir do número **1000** (e pelo menu).
   Se você não escolher nenhum, os canais ficam numerados 1, 2, 3… na ordem da lista.

O menu (segurar OK) também tem: **Meus canais**, **Todos os canais** e **Digitar o número de um canal**
(útil se o controle não tiver teclado numérico).

---

## Como gerar o APK

### Opção A – pelo GitHub (não precisa instalar nada)
1. Crie uma conta grátis em github.com e, no "+" do canto superior, **New repository**.
   Nome: `tvfacil`. Marque **Public** (precisa ser público para o link funcionar na TV). **Create repository**.
2. No computador, descompacte o zip. Na página do repositório, clique em **uploading an existing file**
   e arraste **todo o conteúdo** da pasta `TVFacil` (inclusive a pasta `.github`; no Mac, aperte
   Cmd+Shift+. para ver pastas ocultas). Clique em **Commit changes**.
3. Abra a aba **Actions**. Se pedir, clique em **I understand my workflows, go ahead and enable them**.
   Escolha **Gerar APK → Run workflow**. Espere uns 5 minutos até aparecer o ✓ verde
   (se aparecer ✗ vermelho, abra e copie a mensagem de erro).
4. O APK fica publicado neste link fixo (troque pelos seus nomes):
   `https://github.com/SEU_USUARIO/tvfacil/releases/latest/download/app-debug.apk`
   Para digitar menos na TV, encurte esse link em tinyurl.com e digite o link curto no **Downloader**.

### Opção B – Android Studio
Abra a pasta no Android Studio, espere sincronizar e use **Build → Build APK(s)**.
O arquivo sai em `app/build/outputs/apk/debug/app-debug.apk`.
(Ou pelo terminal: `./gradlew assembleDebug`, com JDK 17.)

---

## Como instalar na TV TCL

1. Na TV: **Configurações → Preferências do dispositivo → Segurança e restrições → Fontes desconhecidas**
   e permita para o aplicativo que você vai usar para abrir o APK (passo 2).
   *(Os nomes dos menus variam um pouco conforme o modelo.)*
2. Escolha um jeito de levar o APK até a TV:
   - **Pen drive**: copie o `app-debug.apk`, espete na TV e abra com um gerenciador de arquivos
     (ex.: *X-plore* ou *File Commander*, instalados pela loja da TV) → **Instalar**.
   - **Send Files to TV** (app grátis no celular e na TV): envia o APK pela rede.
   - **adb** (avançado): ative *Opções do desenvolvedor* (Sobre → toque 7 vezes em "Build"),
     ligue a depuração por rede e rode `adb connect IP_DA_TV` e `adb install app-debug.apk`.
3. Abra o **TV Fácil** pela lista de apps. Dica: deixe-o na primeira fileira da tela inicial.

---

## Se algo der errado

| Problema | O que fazer |
|---|---|
| Lista não baixa | Confira o link/usuário/senha. A tela de configuração mostra o motivo ("Erro: …"). |
| Canais não abrem (lista Xtream) | Edite: exclua a lista e cadastre de novo marcando **Usar formato HLS (m3u8)**. |
| "Este canal não está funcionando agora" | O canal está fora do ar no provedor; tente outro. O app tenta 3 vezes sozinho. |
| Controle sem teclado numérico | Segure OK → **Digitar o número de um canal**, ou use um controle com números. |
| Celular não abre o endereço | Celular e TV precisam estar no **mesmo Wi-Fi/rede**; confira o endereço e o código na TV. |

---

## Para quem for mexer no código

```
app/src/main/java/com/tvfacil/app/
  MainActivity.kt      tela da TV: player (Media3/ExoPlayer), digitar canal, CH+/CH-, mensagens
  SettingsActivity.kt  tela de listas (cadastrar/usar/atualizar/excluir) + liga o servidor do celular
  ConfigServer.kt      mini servidor HTTP na TV (só enquanto a tela de configuração está aberta)
  Playlist.kt          baixa/lê listas M3U e Xtream, guarda cache, filtra só canais ao vivo
  Channel.kt           numeração dos canais ("Meus canais" + automática)
  Store.kt             dados guardados (listas, números escolhidos, último canal)
app/src/main/assets/index.html   página de configuração que abre no celular
```

Ajustes rápidos em `MainActivity.kt`: `TYPE_WAIT_MS` (tempo esperando o próximo dígito, hoje 2,5 s),
`INFO_MS` (quanto tempo o nome do canal fica na tela), `STALE_MS` (de quanto em quanto tempo atualiza a lista).
