<img width="2816" height="536" alt="Gemini_Generated_Image_efwdqmefwdqmefwd" src="https://github.com/user-attachments/assets/b04ffb6f-8f30-4d1d-9c67-591804805cf2" />


# FireSight
*Voice automation for fire department pre-incident inspections* - [Video](https://www.youtube.com/watch?v=-jg3ZX8wHtI) <br>
Submission for [Cactus AI and Google Deepmind's Voice Agent Hackathon at Y Combinator](https://github.com/cactus-compute/voice-agents-hack) by [Ethel Zhang](https://www.linkedin.com/in/ethel-shiqi-zhang/), [Troy Gunawardene](https://www.linkedin.com/in/troy-gwdn/), [Ifeoluwa Oyetimehin](https://www.linkedin.com/in/ifeoluwa-oyetimehin/), and [Arjun Chidambaram](https://www.linkedin.com/in/arjunchidambaram/).

## The Problem We Solve
Fire departments - the real firefighters themselves, not (just) city officials - have to spend countless hours every year performing inspections on buildings called pre-incident surveys. The purpose of these inspections is to assess risk and strategize for potential emergencies. For high-risk buildings like hospitals or schools, these inspections can happen multiple times per year. As part of these inspections, firefighters need to record countless data points in outdated, clunky web forms or even on paper. 

We spoke to real industry professionals and firefighters at departments like FDNY and Colonia for feedback and insights. There's a real need here, and we think we can build a better solution with the technology available to us today.

## How Our Project Works
Rather than making firefighters meticulously type pages of notes into a phone or tablet, FireSight lets the inspector simply speak out loud about what they're looking at. Using AI glasses (e.g. Meta Ray-Bans), the agent can capture pictures to attach to the inspector's comments and make further observations based on the contents. The inspector can also ask the agent questions about what's been documented, what's missing, what existing records show, etc. When the inspection is done, the firefighter can export a PDF report with a single tap.

Moreover, firefighters need to make detailed observations about every nook and cranny, including places like basements, elevators, or electrical rooms that might not have great internet or cell signal. As such, we've built in an  offline AI fallback. Higher-powered AI operations wait for an internet connection, while regular observations and Q&A are supported locally.

## Sample Output
[inspection_20260419_132225.pdf](https://github.com/user-attachments/files/26876369/inspection_20260419_132225.pdf)
