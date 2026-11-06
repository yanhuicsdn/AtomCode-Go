package io.github.yanhuicsdn.atomcode.uploadfixture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

/** Public actions never accept a caller-supplied package, URI, path or content. */
public final class GrantActivity extends Activity {
    public static final String GRANT = Canaries.FIXTURE + ".GRANT";
    public static final String REVOKE = Canaries.FIXTURE + ".REVOKE";
    public static final String CLEAN = Canaries.FIXTURE + ".CLEAN";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean granting = false;
        try {
            String action = getIntent().getAction();
            if (GRANT.equals(action)) {
                if (getPackageManager().getApplicationInfo(Canaries.OWNER, 0).uid == Process.myUid()
                        || getPackageManager().checkSignatures(Canaries.FIXTURE, Canaries.OWNER) != PackageManager.SIGNATURE_MATCH)
                    throw new SecurityException("fixed-owner-identity-mismatch");
                for (int i = 0; i < Canaries.URIS.length; i++) FixtureStore.file(this, i);
                granting = true;
                for (Uri uri : Canaries.URIS) grantUriPermission(Canaries.OWNER, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (REVOKE.equals(action) || CLEAN.equals(action)) {
                for (Uri uri : Canaries.URIS) revokeUriPermission(Canaries.OWNER, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (CLEAN.equals(action)) FixtureStore.clean(this);
            } else throw new IllegalArgumentException("unknown-fixed-action");
            setResult(RESULT_OK);
        } catch (Exception failure) {
            if (granting) for (Uri uri : Canaries.URIS) {
                try { revokeUriPermission(Canaries.OWNER, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (RuntimeException ignored) { /* Host cleanup will independently revoke and verify. */ }
            }
            setResult(RESULT_CANCELED, new Intent().putExtra("failureClass", failure.getClass().getSimpleName()));
        } finally { finish(); }
    }
}
