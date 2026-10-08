# DSH runtime recovery profile

This is the minimal recovery profile currently used by the VPS dsh-agent.service
after the web profile failed to start repeatedly with typert-loader codec errors.

## Purpose

- Load only the built-in @deepseek-ai/dsh-base and @deepseek-ai/dsh-web-app bundles.
- Keep third-party plugins installed in the original web profile, but do not activate them in this recovery profile.
- Do not enable automatic plugin updates while recovering from mixed core/plugin API versions.
- Treat this as a recovery baseline, not as proof that all desired capabilities are enabled.

## Runtime selection

The VPS systemd drop-in dsh-agent.service.d/10-recovery-profile.conf sets:

    [Service]
    ExecStart=
    ExecStart=/usr/local/bin/dsh --profile recovery

The profile itself lives under ~/.dsh/profiles/recovery/.

## Reintroducing plugins

Do not bulk-enable plugins or grant blanket compatibility exemptions. For each candidate:

1. Verify its peerDependencies against the runtime package versions.
2. Confirm the plugin uses the current Cordis/Typert API.
3. Add one plugin (or a small isolated batch) to a test profile.
4. Start the profile and check the full startup log for codec, service, and import errors.
5. Run an authenticated UI smoke test and a feature-specific functional test.
6. Promote only after repeated start/stop and regression checks pass.

The previous web profile is preserved with its dependencies and configuration; the recovery process does not uninstall its packages or delete its data.
