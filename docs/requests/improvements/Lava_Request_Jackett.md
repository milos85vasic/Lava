What we now is the following to incorporate: for start fetch and pull the latest
version of constitution and all other Submodules. make sure we are following all
mandatory rules and constraints and that we are not ignoring or violating any of
them! then, incorporate all supported types of the tests which will for every
supported provider we have perform onboarding, perform various searches for only
htese particular provider being tested, then surf through the results and validate
and verofy the content. we MUST confirm taht valid download options are accessible
and working - download link (valid and not broken), access to torrent file download
(really downloads the torrent file which is nonempty and valid) or gives us magnet
link which is 100% valid! We must through newly added tests replace need for manual
testing by real human! we must write down besides this main scenario all use cases
which real human user would do, then test all this cases, all cases around it, all
variations and edge cases! if we have not incorporated yet HelixQA we must do it now:
https://github.com/HelixDevelopment/HelixQA (git@github.com:HelixDevelopment/helixqa.git).
HelixQA is fully under our control like all other Submodules from vasic-digital and
HelixDevelopment organizations! We MUST create comprehensive test suites and banks of
tests which HelixQA will execute and confirm that all features we have been mentioning
are fully functional! Besides test banks HelixQA MUST perform full autonomous QA
sessions related to each provider, brosing searching, obtaining the download access!
If anything we want to achieve is missing, or we still do not have needed functionality
yo do it, we MUST extend all our Submodules or main project so the means are there! All
tests MUST produce real results! There cannot be any false results or bluff of any kind!
Every test we execute with success MUST produce real rock-solid physical proofs of all
functionalities, fixes, changes or improvements really working as expected! Everything
MUST produce the real evidence! Make sure that we follow code reviw agents rules /
constraints in our work flow(s) defined in our root constitution Submodule! Make sure
we provide bridge to HelixQA (which MUST be already implemented in it) from you (Claude
Code) to provide all LLM and Vision and other model needs to it! You will then be able
to track and monitor in real time EVERYTHIG that HelixQA is doing, to apply additional
improvements, fixes, fill the gaps or fix any detected shortcoming in HelixQA itself and
the main project!

We are providing now credentials you can add into the .env file to access various
trackers:

nobody85perfect, ironman1985 <--- RuTracker
i@mvasic.ru, ironman1985 <--- RuTor
nobody85perf, 01G@mY0n1Y10v3!IpT <--- IPTorrents
nobody85perfect, ironman1985 <--- NNMClub

Make sure none of them leaks through Git versioning or through the logs! Use credentials
for all 4 providers / trackers! For the ones that we still do not support, incorporate
fully those providers! Do in-depth analysis and research of Jackett
(https://github.com/Jackett/Jackett, git repo: git@github.com:Jackett/Jackett.git).
There are already supported various trackers (providers)! It would be ideal if we can add
Jackett as Submodule and then directly to create Providers (provider / tracker
implementations) which will directly reference all this codebase so we do not have to
port it! If that is not possible, port all supported providers / trackers from Jacket and
make sure all of them are fully covered with comprehensive tests, Challenges and HelixQA
test banks and full autonomous sessions! Do as much as needed deep web research to achieve
all described goals! Feel free to incorporate additional 3rd party libraries and solutions
and new approaches we could use to properly test our features! We MUST make sure that
EVERYTHING is fully decoupled, abstractions, all implementations, so new implementation /
flavors and strategies are easy to implement! Especially all providers / trackers! Another
thing that MUST be kept in mind is that in near feature we MUST BE able to add new providers
by installing Android extendion APKs or use any relevant technology to achieve this! All
providers MAY BE hosted / published to Google Play Store or other markets under same or
different development accounts and created by the same or community driven teams! For all
this we MUST maximally extend all documentation, user guides and manuals, architerctural
materials, all diagrams and scehemes, SQL defintions and all other materials! Make sure we
regularly commit and push all Submodules and main repo to all upstreams! Aim for maximal
tests coverage by all supported test types of all our codebase - every single line! Keep
us updated about the progress and all relevant events!
