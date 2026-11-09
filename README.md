<div align="center">

<a href="https://mihon.app">
    <img src="./.github/assets/logo.png" alt="Mihon logo" title="Mihon logo" width="80"/>
</a>

# Mihon [App](#)

### Full-featured reader
Discover and read manga, webtoons, comics, and more – easier than ever on your Android device.

[![Discord server](https://img.shields.io/discord/1195734228319617024.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/mihon)
[![GitHub downloads](https://img.shields.io/github/downloads/mihonapp/mihon/total?label=downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://mihon.app/download)

[![CI](https://img.shields.io/github/actions/workflow/status/mihonapp/mihon/build.yml?labelColor=27303D)](https://github.com/mihonapp/mihon/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/mihonapp/mihon?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/mihon?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/mihon/)

<div align="left">

> [!NOTE]
> **Personal fork, not an official Mihon release.**

## What this fork is for

Mihon is good at reading what you already know about, but it doesn't help you find what to read next. This fork adds that: **Browse → Discover**, a tab that suggests manga **not in your library** based on what you actually read, and says why it picked each one.

- **For you:** what other readers recommend for titles you finished, are reading or have saved, plus picks from your recent searches ("Because you read Kingdom", "Because you searched Frieren").
- **Trending:** what's popular this week, month or 3 months.
- **Top rated:** the best rated in your genres, or in one of your favourite categories.
- **One line on every card saying why** ("Because you read Kingdom", "#3 on MangaUpdates this week"), and **hashtag filters** at the top (`#finished`, `#readers-pick`, `#same-author`, `#action`…).
- **Settings → Personalised** to choose what it's based on.

**How it stays polite:** recommendations come from [MangaUpdates](https://www.mangaupdates.com)' public data (no login needed), not from scraping manga sites. Sites are only searched when you tap a title. MangaUpdates is asked at most 30 times an hour, a few at a time, and answers are saved for a day or a week. Novels and doujinshi are left out.

**Where it's going:** this is a personal build for now. The plan is to offer the useful parts to Mihon as small, separate pull requests ([recommendations request: #646](https://github.com/mihonapp/mihon/issues/646)).

Code: the [`feature/browse-suggestions`](../../tree/feature/browse-suggestions) branch. Test builds: [Releases](../../releases).

</div>

## Download

[![Mihon Stable](https://img.shields.io/github/release/mihonapp/mihon.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://mihon.app/download)
[![Mihon Beta](https://img.shields.io/github/v/release/mihonapp/mihon-preview.svg?maxAge=3600&label=Beta&labelColor=2c2c47&color=1c1c39)](https://mihon.app/download)

*Requires Android 8.0 or higher.*

## Features

<div align="left">

* Local reading of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MangaBaka](https://mangabaka.org), [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/), and [Hikka](https://hikka.io/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.
* Plus much more...

</div>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

Before reporting a new issue, take a look at the [FAQ](https://mihon.app/docs/faq/general), the [changelog](https://mihon.app/changelogs/) and the already opened [issues](https://github.com/mihonapp/mihon/issues); if you got any questions, join our [Discord server](https://discord.gg/mihon).


### Repositories

[![mihonapp/website - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=website&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/website/)
[![mihonapp/bitmap.kt - GitHub](https://github-stats-extended.vercel.app/api/pin/?username=mihonapp&repo=bitmap.kt&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/bitmap.kt/)

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
