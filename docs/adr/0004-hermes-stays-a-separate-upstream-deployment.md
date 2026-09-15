# Hermes stays a separate upstream deployment

The agents image bakes in every tool, so running the Hermes gateway and dashboard in the same container looked natural. We keep Hermes as its own deployment of the upstream `nousresearch/hermes-agent` image instead. The agents image only installs the Hermes CLI, configured to use the in-cluster Hermes service. The upstream image starts as root under s6-overlay and has its own capability set, volume and OIDC client. Merging it would mean maintaining a fork of its startup, and one Hermes would stop working whenever the other failed.

## Consequences

- There is one Hermes state and memory, owned by the Hermes deployment. Agent Sessions do not keep a local Hermes state.
