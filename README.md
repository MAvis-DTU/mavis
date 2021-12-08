# Introduction to MAvis

MAvis is short for "Multi-Agent Visualisation Tool". It is a tool to support the design, implementation and test of single- and multi-agent planning algorithms. Various discrete single-and multi-agent domains can be modelled and visualised with the tool. The instances of a given domain are called **levels**, and these are typically what is referred to as **planning problems** or **planning tasks** within the area of automated planning (a given _goal_ has to be reached from a given _initial state_ through a sequence of _actions_). The tool doesn't provide any planning system for solving levels, it only provides the models of the different domains and a graphical user interface to visualise levels and animate solutions. The system for visualising levels and solutions is called the (**MAvis**) **server**. Users of the tool (e.g. students or researchers in AI) then have to provide their own AI system (planning system) for solving levels. That AI system is called a **client**. 

The original domain developed for MAvis is called the **hospital domain**. It is a multi-agent domain where agents have to move boxes into designated goal cells. Each goal cell has to be matched by a box of the same letter, and each agent can only move a box of the same color as itself. A level can also contain goal cells for the agents, hence making the domain into a generalisation of the _multi-agent pathfinding_ problem (Stern et al, 2019). The following video shows a client (Croissant) solving a level (MAAIoliMAsh) in the hospital domain:

https://user-images.githubusercontent.com/11925062/127993622-61a5fb10-ac0f-4a3a-9de8-e314a96c2b67.mov

Both client and level are made by students attending the course _02285 Artificial Intelligence and Multi-Agent Systems_ at the Technical University of Denmark (DTU). The solution found above has a length of 85 joint moves, which is not optimal. Another client made by students in the same course found a solution of length 80, but that is so far the shortest known solution. The first version of MAvis was developed in 2010 to be used as a teaching tool in the mentioned course at DTU. The original version was implemented with a fixed domain, the hospital domain. The hospital domain is still the main domain used in MAvis, but the current version of the tool supports the development of a wide range of other multi-agent domains. A detailed description of the hospital domain is available in [hospital_domain.pdf](docs/domains/hospital/hospital_domain.pdf). Each iteration of the course ends with a competition where the clients made by the students compete in solving as many levels as possible as efficiently as possible (where the competition levels are also designed by the students themselves). The clients are both scored on computation time (how long it took to solve the problem) and solution quality (number of joint moves used).  

Multi-agent clients for the hospital domain are typically based on advanced multi-agent architectures involving communication, task decomposition, task sharing, etc. The most basic levels can however also be solved with simpler algorithms like Breadth-First Search (BFS). A client based on BFS is guaranteed to only find optimal solutions, but will generally not scale to levels with many agents and boxes. By restricting the set of legal (applicable) moves, we can apply a simple BFS client to find optimal solutions in the classic Sokoban puzzle game. In Sokoban, only straight pushes are allowed, and no pulls. Here is a visualisation of the solution found by a BFS client to the last level of the Sokogen variant of Sokoban, [Sokogen level 78](https://www.sokobanonline.com/play/web-archive/jacques-duthen/sokogen-990602-levels/87496_sokogen-990602-levels-78):

https://user-images.githubusercontent.com/11925062/128046889-5bd59281-97bd-4776-a7a8-4d8a3c964512.mp4

A final example is the multi-agent level below with a lot of potential for conflicts between the 4 agents, as seen in the solution of the client to the left. The client on the right on the other hand elegantly solves the conflicts between the agents, and produces a faster solution (fewer joint moves). Both clients were contestants in the competition of the 2021 iteration of the course _02285 Artificial Intelligence and Multi-Agent Systems_. Note that neither of the clients have managed to find an optimal goal prioritisation (decision on which boxes to put on which goal cells in which order). 

https://user-images.githubusercontent.com/11925062/128043884-608c2020-95ec-41f4-8088-170c6cf1c4df.mp4

# Building MAvis
MAvis is developed in Java using an IntelliJ IDEA project.

To build MAvis you will need to have installed:
* IntelliJ IDEA (https://www.jetbrains.com/idea/)
* JDK 17 or newer (e.g. https://openjdk.java.net/)

You can get IntelliJ to download a JDK for you during step 4 in the next instructions, in case you don't want to download and install it yourself.

The build steps are then:
1. Clone this repository.
2. Launch IntelliJ.
3. Select "File -> Open..." and choose the folder you cloned the repository into.
4. Configure an SDK in IntelliJ (https://www.jetbrains.com/help/idea/sdk.html):
   1. Select "File -> Project Structure..."
   2. Open the "SDKs" tab.
   3. Create a new JDK by clicking the plus symbol in the top; add or download per your preference. Name the new SDK "MAvis JDK".
   4. Confirm in the "Project" tab that your configured SDK is used as the "Project SDK".
5. Select "Build -> Build Project".

You should now see the project built in the `out/` folder. You find the jar file as `out/server.jar`.

**Lamenting step 4 above**: SDKs in IntelliJ are global, but a project defines an SDK to use and this choice is under version control in the `.idea/misc.xml` file. This ultimately means that everyone who wants to build the project must either:
* Configure a global SDK with the same name, or
* Change the project's chosen SDK to a configured SDK of choice.

The second option causes you to have modified a tracked file that shouldn't be committed back, leaving your git status cluttered. `git commit -a` has become a trap, and I'm not aware of any good way to work around this.

We recommend you opt for the first option to manage the project's SDK, since it requires a one-time effort at first setup and then gets out of your way. If you have a thousand IntelliJ projects that follow this practise, then you might be inconvenienced by the long list of global SDKs. Sorry about that.

Ideally, the SDK for the project would not have been under version control.

# Running MAvis
Assuming you built MAvis and have a terminal with the current working directory in the project's root folder, you can start with:

    $ java -jar out/server.jar -h

This will show you a detailed help description. There are many ways to run the server. You can try a simple run with:

    $ java -jar out/server.jar -c "java src/TestClient.java" -l "levels/hospital/MAAIMAS.lvl" -g

The `TestClient.java` client outputs 20,000 random (joint) actions, so the agents will not be doing anything intelligent, but you can watch them shuffle about for a bit.

If you want to see something more advanced, then you can write a client! Instructions for the hospital domain is found in [hospital_domain.pdf](docs/domains/hospital/hospital_domain.pdf). Good luck and have fun ^_^

# References
Roni Stern, Nathan R. Sturtevant, Ariel Felner, Sven Koenig, Hang Ma, Thayne T. Walker, Jiaoyang Li, Dor Atzmon, Liron Cohen, T. K. Satish Kumar, Roman Barták, and Eli Boyarski. Multi-agent pathfinding: Definitions, variants, and benchmarks. In _Proceedings of the 12th International Symposium on Combinatorial Search (SoCS)_, pages 151–159, 2019.
