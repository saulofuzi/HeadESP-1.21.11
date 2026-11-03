# Head ESP — Minecraft Fabric 1.21.11

Mod apenas para o **cliente**. Detecta cabeças de jogadores (PLAYER_HEAD e PLAYER_WALL_HEAD) em chunks carregados, até 32 blocos na horizontal e 20 na vertical, e desenha um contorno vermelho.

- **H**: ativa/desativa o ESP.
- **J**: restaura as cabeças ignoradas.
- Clique esquerdo ou direito numa cabeça para ignorá-la. O bloco não é removido.
- A lista de ignoradas é temporária e limpa ao trocar de mundo/dimensão.

## Compilação

Requer Java 21 e internet. Para compilar localmente, execute `gradle build` na pasta do projeto.

O GitHub Actions também tenta compilar automaticamente a cada push. Quando houver uma compilação bem-sucedida, baixe o artefato `HeadESP-1.21.11-jar` na aba Actions.

**Importante:** o primeiro build ainda precisa ser validado. Se a API Fabric/Minecraft 1.21.11 tiver diferenças, os logs do Actions vão mostrar o erro para corrigirmos.
