# Lava additional work

We have identified points in the System that have to be fixed, polished or improved.
We are providing the full list of work that has to be tackled: imprefections, bugs, things to change, improve or polish!

## Lava API secure access

Lava API must have secure access with private key or other proper auth. mechanism. Clients MUST have valid access credentials (or key / token) to use it. 
No one can use the API without of approval, valid credentuals - the client applicatoins or certified 3rd parties.
Inside the client app(s) we shall have as binnary data (the private key) as a part of app(s) source code. 
Key will be used to validate and verify the app against the API. Key will be inside the request body as one of properties sent every time we communicate with the API.
Key can be UUID which will be validated on backend side. If client sends invalid key in request, that client will be blocked for proper time period after which he can try again to execute the API call!
Block time can be smaller at first, 2s, then 5, then 10s, 30s, 0ne minute, one hours, etc.
We MUST be sure that API uses http3 (quic) with brotli compression. 
All requests and responses MUST be in encrypted body paylod (we MUST enable HTTPS for that purpose)! Development containers MUST use this too!
API can load the list of approved keys (UUIDs) fron .env file. 
Client app will when builidng obtain from same .env file UUID which corresponds to. 
.env file shall have all UUIDs for laoding in following format:
"CERTIFIED_CLIENT_NAME:UUID_VALUE". For Android client this can be: "android:UUID_VALUE". API MUST store this information fully and safely encrypted! 
Client (android) shall during the build process create proper static class with property with assigned binnary data (encrypted). This class is generated code during the build process.
When performing requests towards the API client app(s) will decrypt binnary data into UUID which will be put into the request. 
WE MUST never keep stored or longer time in the memmory the unencryped UUID value!
So basically every time we have to execute API request we will be performing decryption of UUID and after call is executed, that variable will be released from memory!

Search and other requests MUST accept the parameter of provider to work with! It can be: All (searching all providers and returning results), or just one or multiple choisen providers, for example 3 of 5, 2 of 10, and so on.
Results MUST be returned in real time as events, UI / UX of clients MUST present results as they are being received!
For events we could use gRTC, WebSockets, or any appropriate technology! Events endpoints MUST be protected same like the regular REST API calls!

## Receiving results in client(s) app(s)

Received results will be presented with addition of label (we do not have one now) - which provider result belongs to (RuTracker, RuTor, etc)

## Search request from client(s) app(s)

When searching user MUST be able to chose as option: 'All', or any single of available provider by checking them (possible to choose multiple providers at once). 
We MUST present only providers which are configured in Settings with working access!

## Client app(s) first run0

When user opens client app(s) for the first time he is asked to chose provider. However, he MUST enable one or more providers. For each user MUST be able to configure credentials!
Since there can be one or more providers, this phase MUST become onboarding wizard. Leaving wizard without configured providers should close the app.
Until there is at least one configured provider user cannot go to main home screen!
User MUST chose one or more providers, then the proper Next button shall be enabled!
Click on Next will open setup screen for the first chosen provider.
On that screen user will create new or use existing credentials / API key or token.
After provider is configured, user MUST execute connection tests with provided credentials / API key or toke with success.
User can choose anonymous access if that is possible for particular provider!
After connection tests is executed with success the Next button will be enabled to go to next chosen provider for the configuration.
When the last provider is configured, user can tap on Finish button.
If user does not want to configure all chosen providers and has at least one configured in the flow, he can press finish button any time (or next if he wants to configure the next provider).
Finish button closes the setup wizard and brings the user to home screen. Killing and restarting the app will bring user always to home screen as long there is at least one provider configured and working!
User can always configure existing providers or enable new ones from Settings in later stages of app use!

## API error

We have installed client android app clean, onboarded Internet Archive provider and chosen our Lava API distributed to thinker.local.
Then, we have tried to search from Search tab for 'alice'. We got error: "Something went wrong, please try again later".
We MUST investigate issue and apply proper fix addressing all root causes! This could be provider related or related to the whole API or its distribution!
Create proper tests which will perform on Core level and UI / UX (full automation) level(s) testing - search with some common words like we just did.
We MUST confirm that we are getting valid results! We MUST also test with some rubbish query to get zero results too!
All this MUST be validated and verified!
Add proper Crashlytics non-fatal tracking to all places inside the client app(s) and API(s) / backend so in later stages we can obtain all recorded data and apply additional fixing and polishing!

## Search result filtering on client(s) app(s)

Filtering of search results MUST have possibility of filtering results by all providers who got us results, or by just selected providers who are enabled in the system.
For example user may want to filter only RuTracker or RuTore results.

## Polishing UI / UX of the Menu screen / tab

On the top we now see only one logged in provider, however since we will be able to use multiple providers, we MUST present every single signed in provider with username / anonymous title and sign out button!

## Additional color themes / color schemes

We now have only one color scheme available in dark and light scheme. We MUST add more schemes that user can chose besides this one which MUST stay default.
Port color schemes from ../Boba and ../MeTube projects. Investigate what palettes do exist there and incorporate them into all client apps (android).
User MUST be able to pick any of them from drop down or some other nice UI / UX component and color scheme to be applied immidiately as it is chosen.
Chosen color scheme or theme MUST be remembered as soon as it has been chosen! If user kills the app and opens it again, last chosen theme and color scheme MUST be applied!

## About dialog extension

Extend About dialog to contain version code with version too! For example: Version: 1.2.7 (1016).
It is a simple and important change which brings this detail available for end users and the QA team!

## Sync now button

Add to the right of Favorites sync and Bookmarks sync nice little sync button so sync process for particular category is triggered immidiately.
Make sure user cannot tap it multiple times until sync completes.
If sync fails proper error toast must be presented.

### Additional sync options

We MUST add several more things taht users can sync!
Users MUST be able to sync Provider Credentials and History as well!
Every device user has MUST be uniquely identified, so next time user installs the app on same device from zero (clean), if he has performed sync to backend, 
he will pull all information as soon as application runs and backend is detected and communication established.
If user runs fresh install (for the first time), and we have communication with detected backend, and all users data is pulled / loaded  / synced, instead showing onboarding screen, 
if there is at least one working provider (we must validate each pulled - test connection), user will be taken to the main screen.
All selected providers MUST be synced as well.
Each of these categories MUST have sync settings like Favorites and Bookmarks have with Sync Now button we are incorporating!

## Credentials screen

Credentials screen has ugly UI and terrible UX! Thus MUST be improved so it is nice looking, clean and inutitve!
Pay attention that on new versions of Android 3 buttons navigation is displayed over this screen over the plus floating button! This MUST be fixed, and on every other screen wher this could happen!

## Testing

All changes we do on the API(s) and client(s) app(s) MUST BE heavility covered with all supported types of the tests and Challenges!
Everything MUST be covered on a level of the Core, Component and the APIs or client(s) app(s)! 
We MUST have full automation tests ran on real emulators for all major Android (and other supported platforms) evrsions!
Any issue that pops up MUST BE properly addressed and all root causes / issues fixed and covered with comprehensive validation and verification tests!

### Important notes

IMPORTANT: Make sure that all existing tests and Challenges do work in anti-bluff manner - they MUST confirm that all tested codebase really works as expected! 
We had been in position that all tests do execute with success and all Challenges as well, but in reality the most of the features does not work and can't be used! 
This MUST NOT be the case and execution of tests and Challenges MUST guarantee the quality, the completition and full usability by end users of the product! 
This MUST BE part of Constitution of our project, its CLAUDE.MD and AGENTS.MD if it is not there already, and to be applied to all Submodules's Constitutuon, CLAUDE.MD and AGENTS.MD as well (if not there already)!
