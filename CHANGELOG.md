# Changelog

## [0.17.0](https://github.com/JorisJonkers-dev/agents-api/compare/v0.16.0...v0.17.0) (2026-06-28)


### Features

* **002:** durable sessions — restart with full history ([#17](https://github.com/JorisJonkers-dev/agents-api/issues/17)) ([409f4c5](https://github.com/JorisJonkers-dev/agents-api/commit/409f4c5f20cfbb8e731555dbf1e15cb4de2a5c90))
* **003:** agent console redesign + live session-status SSE ([#18](https://github.com/JorisJonkers-dev/agents-api/issues/18)) ([3d21f41](https://github.com/JorisJonkers-dev/agents-api/commit/3d21f4168ab94dfac7092e1cb8635111753c89cc))
* **008:** emitted telemetry contract for agents observability ([#20](https://github.com/JorisJonkers-dev/agents-api/issues/20)) ([84860cc](https://github.com/JorisJonkers-dev/agents-api/commit/84860cc4d42d11b797c6a35161edd420a6782c8e))
* **010:** versioned agent setup management with restart-into-setup ([#19](https://github.com/JorisJonkers-dev/agents-api/issues/19)) ([dac68db](https://github.com/JorisJonkers-dev/agents-api/commit/dac68dbbeb89c07688f6246dd093bb04266ffa88))
* **012:** session delete, controls rail with edge arrow, mobile fullscreen ([#29](https://github.com/JorisJonkers-dev/agents-api/issues/29)) ([75d011c](https://github.com/JorisJonkers-dev/agents-api/commit/75d011c7d7694a7f634c0aac6da00e2679c9cc9c))
* **015:** decouple RAG retrieval/capture seam ([#62](https://github.com/JorisJonkers-dev/agents-api/issues/62)) ([29efb92](https://github.com/JorisJonkers-dev/agents-api/commit/29efb9281665ab4b0c3f2796be0267c609f98571))
* **023:** decouple chat generation behind a port + multi-turn history ([#63](https://github.com/JorisJonkers-dev/agents-api/issues/63)) ([30231bc](https://github.com/JorisJonkers-dev/agents-api/commit/30231bcc8ea5bbd31ae509beb828154baccf93d7))
* **024b:** runner-Pod chat generation backend (flag-gated, default off) ([#67](https://github.com/JorisJonkers-dev/agents-api/issues/67)) ([cf72e96](https://github.com/JorisJonkers-dev/agents-api/commit/cf72e969acaf1514849eb1284361f4c0ea85957b))
* **024c:** true token-level streaming for runner-Pod chat ([#68](https://github.com/JorisJonkers-dev/agents-api/issues/68)) ([7e902f8](https://github.com/JorisJonkers-dev/agents-api/commit/7e902f8d432c89b1042caa44fa8bf5638a06afa2))
* **agent-runner:** inject CLAUDE_CODE_OAUTH_TOKEN from the portal-managed Secret ([#92](https://github.com/JorisJonkers-dev/agents-api/issues/92)) ([4b0d750](https://github.com/JorisJonkers-dev/agents-api/commit/4b0d7502d08e4482463647be79f77d55780935ab))
* **agents-api:** auto-recycle disconnected runners onto new releases ([#45](https://github.com/JorisJonkers-dev/agents-api/issues/45)) ([7fe0057](https://github.com/JorisJonkers-dev/agents-api/commit/7fe00571994ee3903d0c8bc768d30f948c0a2ae5))
* **agents-api:** identify repositories by URL, not name ([#142](https://github.com/JorisJonkers-dev/agents-api/issues/142)) ([b3df275](https://github.com/JorisJonkers-dev/agents-api/commit/b3df27561a8873a85dfe350bce0f1f7392b174b9))
* **agents:** add the Credentials page for CLI re-authentication ([#83](https://github.com/JorisJonkers-dev/agents-api/issues/83)) ([c933425](https://github.com/JorisJonkers-dev/agents-api/commit/c933425101f432d431a6cab370863a8ac204c5d3))
* **agents:** continue a workspace onto an updated agent-runner image (spec 024) ([#95](https://github.com/JorisJonkers-dev/agents-api/issues/95)) ([2f640d3](https://github.com/JorisJonkers-dev/agents-api/commit/2f640d3de987bb86dcbae964507cadebb7e71a00))
* **agents:** GitHub App-only repository access (spec 025) ([#103](https://github.com/JorisJonkers-dev/agents-api/issues/103)) ([41ea063](https://github.com/JorisJonkers-dev/agents-api/commit/41ea063261709e926a62c0272de49df8f30e4ab5))
* **agents:** report runner image by release version, not digest ([#97](https://github.com/JorisJonkers-dev/agents-api/issues/97)) ([e850b71](https://github.com/JorisJonkers-dev/agents-api/commit/e850b716b501ca77fbb40023151489ba6b59e949))
* **agents:** user-scoped credential store + workspace-pane improvements ([#111](https://github.com/JorisJonkers-dev/agents-api/issues/111)) ([e98b0d1](https://github.com/JorisJonkers-dev/agents-api/commit/e98b0d162147a080f6a1b026161cc4f10b0ce09f))
* **credentials:** redesign sign-in cards, add a stored-credential check, surface success ([#90](https://github.com/JorisJonkers-dev/agents-api/issues/90)) ([6ef97a9](https://github.com/JorisJonkers-dev/agents-api/commit/6ef97a9d1af980e88528b2a29798289e038978a0))
* extract + rename the agent stack into ExtraToast/agents (spec 001) ([#2](https://github.com/JorisJonkers-dev/agents-api/issues/2)) ([c902904](https://github.com/JorisJonkers-dev/agents-api/commit/c902904079c241162fb2cec265092be9dd085db1))
* only recycle a stale runner once its agent has gone idle ([#47](https://github.com/JorisJonkers-dev/agents-api/issues/47)) ([41ebf6e](https://github.com/JorisJonkers-dev/agents-api/commit/41ebf6e469982d0b4a5d15c8a9ad2d07576ac894))
* **rebrand:** migrate agents-api to JorisJonkers-dev coordinates ([#1](https://github.com/JorisJonkers-dev/agents-api/issues/1)) ([988a5f6](https://github.com/JorisJonkers-dev/agents-api/commit/988a5f64e4e4757cadbb6e6afb871fd82e1f9192))


### Bug Fixes

* **agents-api:** derive credential updatedBy from the owner, not the request body ([#119](https://github.com/JorisJonkers-dev/agents-api/issues/119)) ([f718a2a](https://github.com/JorisJonkers-dev/agents-api/commit/f718a2a49b64bd30a8a851ca5051e2243e7f82c7))
* **agents-api:** don't 502 session launch when nodes:list is denied ([#36](https://github.com/JorisJonkers-dev/agents-api/issues/36)) ([a5c5b8d](https://github.com/JorisJonkers-dev/agents-api/commit/a5c5b8deb2376c6d0e79943eb8c4296aa359fce8))
* **agents-api:** eliminate 409 session-generation-conflict on new-session create ([#59](https://github.com/JorisJonkers-dev/agents-api/issues/59)) ([b51b3e5](https://github.com/JorisJonkers-dev/agents-api/commit/b51b3e5bd704c67e1b1993988fc43c8c24a0d8ee))
* **agents-api:** make codex config.toml optional ([#135](https://github.com/JorisJonkers-dev/agents-api/issues/135)) ([6dcfe1f](https://github.com/JorisJonkers-dev/agents-api/commit/6dcfe1f0766ac24b811962ec17355fd5f7f6de92))
* **agents-api:** pin runner node-selector to personal-stack/* labels ([#37](https://github.com/JorisJonkers-dev/agents-api/issues/37)) ([54aa1a7](https://github.com/JorisJonkers-dev/agents-api/commit/54aa1a75bf2da0ea205d7ef71c5f04389e89599e))
* **agents-api:** reap stale runner-setup leases so a crash mid-provision can't wedge a workspace ([#38](https://github.com/JorisJonkers-dev/agents-api/issues/38)) ([4c923f3](https://github.com/JorisJonkers-dev/agents-api/commit/4c923f373b94a1e70faffcaaf580ab81dbcd13af))
* **agents-api:** relay credential-worker errors as problem+json ([#88](https://github.com/JorisJonkers-dev/agents-api/issues/88)) ([6881f5b](https://github.com/JorisJonkers-dev/agents-api/commit/6881f5bf1535fd9b60ebed9fe9b95e6dd4b6ef48))
* **agents:** inject full Claude subscription credential into runners ([#121](https://github.com/JorisJonkers-dev/agents-api/issues/121)) ([6d0cd25](https://github.com/JorisJonkers-dev/agents-api/commit/6d0cd2504fa75dd96644b8059afa6870a1fea97d))
* **agents:** persist Claude+Codex session state across runner Pod recreation ([#130](https://github.com/JorisJonkers-dev/agents-api/issues/130)) ([d357048](https://github.com/JorisJonkers-dev/agents-api/commit/d35704880f68fff53e8eb2fa92c215dbd298abbe))
* **agents:** resume the session on attach when a reprovisioned runner is still booting ([#101](https://github.com/JorisJonkers-dev/agents-api/issues/101)) ([f22eca4](https://github.com/JorisJonkers-dev/agents-api/commit/f22eca450cd182c62345fbf7a08176e00c06775c))
* **agents:** wait for the old runner pod to terminate before reprovision ([#99](https://github.com/JorisJonkers-dev/agents-api/issues/99)) ([50d2cb9](https://github.com/JorisJonkers-dev/agents-api/commit/50d2cb91857e003e04d0e1160bffec90c99c5491))
* **ci:** repair L5 JVM gate ([#4](https://github.com/JorisJonkers-dev/agents-api/issues/4)) ([cf0338d](https://github.com/JorisJonkers-dev/agents-api/commit/cf0338d6f8f2ad9242f248815c7252d1bc5e9775))
* **sessions:** resume the prior Claude & Codex conversation on revival ([#78](https://github.com/JorisJonkers-dev/agents-api/issues/78)) ([9ba52f2](https://github.com/JorisJonkers-dev/agents-api/commit/9ba52f29cf35aad004163f11e549266b86526021))


### Performance Improvements

* **terminal:** coalesce output writes and ship fewer, larger frames ([#40](https://github.com/JorisJonkers-dev/agents-api/issues/40)) ([983f019](https://github.com/JorisJonkers-dev/agents-api/commit/983f0192652adcb1e8d8139067a2d40861b5449f))
