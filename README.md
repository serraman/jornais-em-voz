# Jornais em Voz

App Android que lê em voz alta, no Android Auto, os títulos dos jornais recebidos em PDF pelo WhatsApp. Se um título interessar, carrega no botão e ouve a notícia completa.

## Como funciona

1. **De manhã:** no WhatsApp, abra o PDF do jornal (ou mantenha o dedo em cima dele) > **Partilhar** > **Jornais em Voz**. Repita para cada jornal.
2. A app envia cada página ao **Gemini** (Google), que separa as notícias. Um jornal de 40 páginas demora cerca de 5 a 10 minutos. Quando estiver pronto, aparece uma notificação.
3. **No carro:** abra **Jornais em Voz** no Android Auto e escolha o jornal (ou um título).
4. A app lê os títulos, com uma pausa de 3 segundos entre eles.

| Durante…                | Botão / comando                                             | Resultado                     |
|-------------------------|-------------------------------------------------------------|-------------------------------|
| um título ou a pausa    | **Seguinte** (volante), "Ok Google, seguinte" ou **Ler notícia** | lê a notícia completa         |
| a notícia completa      | **Seguinte** ou **Próximo título**                          | salta para o título seguinte  |
| um título               | **Próximo título**                                          | passa ao título seguinte      |
| qualquer momento        | **Anterior**                                                | volta ao título anterior      |

No fim da notícia completa, a leitura continua no título seguinte.

## Instalação (uma vez)

1. **Descarregar o APK:** no telemóvel, abra a página *Releases* deste repositório e descarregue o ficheiro `JornaisEmVoz-vN.apk` mais recente. Abra-o e autorize a instalação (o Android pede para permitir "instalar apps desconhecidas" ao navegador).
2. **Chave Gemini:** em https://aistudio.google.com/apikey crie uma chave grátis. Na app, abra **Definições**, cole a chave e carregue em **Testar chave e escolher modelo**. Depois carregue em **Guardar**.
3. **Voz portuguesa:** em Definições do telemóvel > Idiomas > Saída de texto para voz, escolha o motor **Google** e instale a voz **Português (Portugal)**.
4. **Android Auto:** as apps instaladas fora da Play Store só aparecem depois deste passo:
   - abra a app Android Auto no telemóvel (Definições > Dispositivos ligados > Android Auto);
   - desça até **Versão** e toque 10 vezes até aparecer "Definições de programador";
   - menu **⋮** > **Definições de programador** > ative **Fontes desconhecidas**.
5. **Bateria (Xiaomi/Redmi):** Definições > Apps > Jornais em Voz > Poupança de bateria > **Sem restrições**. Assim o processamento não é interrompido com o ecrã desligado.

## Definições

- **Modelo Gemini:** por omissão `gemini-3.5-flash-lite` (grátis, cerca de 500 páginas por dia). Se a Google o retirar, use o botão *Testar* para escolher outro.
- **Pausa entre títulos:** 3 segundos (1 a 10).
- **Velocidade da voz**, **ler o subtítulo** depois do título, e **dias** que os jornais ficam guardados.

## Atualizar a app

Cada alteração ao código neste repositório gera automaticamente uma nova versão em *Releases*. Basta descarregar e instalar por cima da anterior. Os jornais e as definições mantêm-se.
