# DSH runtime recovery profile

This is the minimal recovery profile currently used by the VPS dsh-agent.service
after the original web profile repeatedly failed with typert-loader codec errors.

## Current active bundles

- Built-in @deepseek-ai/dsh-base
- Built-in @deepseek-ai/dsh-web-app
- dsh-mobile-hanui 0.2.5
- dsh-web-mobile-fix 1.0.6

The two mobile-focused plugins were added to this isolated profile after the core-only
recovery profile started successfully. The service restarted with both enabled and
remained active with zero systemd restarts at the time of verification. This is a
startup smoke test, not a substitute for testing every mobile UI feature in a browser.

## Safety and scope

- Third-party plugins remain installed in the original web profile but are not activated here unless explicitly listed in package.json bundles.
- Automatic plugin updates remain disabled during recovery to prevent lockfile drift.
- This profile is a recovery baseline, not proof that all desired capabilities are enabled.
- The original web profile and its dependencies/configuration are preserved.

## Runtime selection

The VPS systemd drop-in dsh-agent.service.d/10-recovery-profile.conf sets:

    [Service]
    ExecStart=
    ExecStart=/usr/local/bin/dsh --profile recovery

The profile lives under ~/.dsh/profiles/recovery/.

## Reintroducing plugins

Do not bulk-enable plugins or grant blanket compatibility exemptions. For each candidate:

1. Verify peerDependencies against the runtime package versions.
2. Confirm the plugin uses the current Cordis/Typert API.
3. Add one plugin (or a small isolated batch) to a test profile.
4. Start the profile and check the full startup log for codec, service, and import errors.
5. Run an authenticated UI smoke test and a feature-specific functional test.
6. Promote only after repeated start/stop and regression checks pass.
