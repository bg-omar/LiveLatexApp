# Cloud connections (GitHub & Google Drive)

The app can connect to **GitHub** and **Google Drive** from the menu (⋮) under the **Cloud** section.

## GitHub

1. Create an OAuth App at [GitHub Developer Settings](https://github.com/settings/developers):
   - **Authorization callback URL**: `livelatex://github-callback`
   - Note the **Client ID** and create a **Client secret**.

2. Add them to your project (do not commit secrets):
   - In the project root, create or edit `gradle.properties` and add:
     ```properties
     GITHUB_CLIENT_ID=your_github_client_id
     GITHUB_CLIENT_SECRET=your_github_client_secret
     ```
   - Or pass them at build time.

3. In the app, open **Menu → Connect GitHub**. You’ll be sent to GitHub to authorize; after approving, you’re returned to the app and the token is stored (encrypted).

**Security:** For production, do the code‑for‑token exchange on your own backend and avoid putting the client secret in the app.

## Google Drive

1. In [Google Cloud Console](https://console.cloud.google.com/):
   - Create or select a project.
   - Enable the **Google Drive API**.
   - Under **APIs & Services → Credentials**, create an **OAuth 2.0 Client ID** (Android) with your app package name and SHA-1.
   - Optionally create a **Web application** client to get a **Web client ID** for server auth code (for backend token exchange).

2. Add the Web client ID (optional) to `gradle.properties`:
   ```properties
   GOOGLE_WEB_CLIENT_ID=your_web_client_id.apps.googleusercontent.com
   ```

3. In the app, open **Menu → Connect Google Drive** and sign in with a Google account. The app requests the `drive.file` scope (files created or opened by the app).

The signed-in account is stored so you can later add Drive API calls (list files, upload, etc.) using the same credentials.
