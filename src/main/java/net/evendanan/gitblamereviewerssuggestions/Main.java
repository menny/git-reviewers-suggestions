package net.evendanan.gitblamereviewerssuggestions;

import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Main {

    private static final boolean DEBUG_LOG = false;
    private static final int DEFAULT_REVIEWERS_COUNT = 4;

    public static void main(String[] args) throws IOException, GitAPIException {
        File workingFolder = new File(System.getProperty("user.dir")+"/.git");
        if (DEBUG_LOG) System.out.println("git repo at " + workingFolder.getAbsolutePath());
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder.setGitDir(workingFolder).readEnvironment().build();
        Git git = Git.wrap(repository);
        RevWalk revCommits = new RevWalk(repository);
        if (DEBUG_LOG) System.out.println(String.format(Locale.US, "Branch %s", repository.getBranch()));
        ObjectId headCommit = repository.resolve("HEAD");
        RevCommit previousCommitData = revCommits.parseCommit(repository.resolve("HEAD^"));
        List<String> emailsToIgnore = new ArrayList<String>();
        //reading the commit
        RevCommit commitData = revCommits.parseCommit(headCommit);
        if (DEBUG_LOG) System.out.println(String.format(Locale.US, "HEAD commit %s by %s (%s).",
                headCommit.getName(), commitData.getAuthorIdent().getName(), commitData.getAuthorIdent().getEmailAddress()));
        emailsToIgnore.add(commitData.getAuthorIdent().getEmailAddress());
        if (!commitData.getAuthorIdent().getEmailAddress().equals(commitData.getCommitterIdent().getEmailAddress())) {
            if (DEBUG_LOG) System.out.println(String.format(Locale.US, "  committer %s (%s).",
                    commitData.getCommitterIdent().getName(), commitData.getCommitterIdent().getEmailAddress()));
            emailsToIgnore.add(commitData.getCommitterIdent().getEmailAddress());
        }
        //now, let's see changes
        CanonicalTreeParser newTree = new CanonicalTreeParser();
        newTree.reset(repository.newObjectReader(), commitData.getTree());
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(repository.newObjectReader(), previousCommitData.getTree());

        List<DiffEntry> diffs = git.diff().setNewTree(newTree).setOldTree(oldTree).call();
        Map<String, Integer> affectedReviewers = new HashMap<String, Integer>();
        if (DEBUG_LOG) System.out.println("Have "+diffs.size()+" diff entries:");
        for(DiffEntry diffEntry : diffs) {
            switch (diffEntry.getChangeType()) {
                case ADD:
                    if (DEBUG_LOG) System.out.println(String.format(Locale.US, "ADD %s", diffEntry.getNewPath()));
                    //not adding anyone
                    break;
                case DELETE:
                    if (DEBUG_LOG) System.out.println(String.format(Locale.US, "DELETE %s", diffEntry.getOldPath()));
                    //adding the owners of the old file
                    addAuthorsFromBlame(affectedReviewers, git.blame().setFilePath(diffEntry.getOldPath()).call(), emailsToIgnore);
                    break;
                case COPY:
                    if (DEBUG_LOG) System.out.println(String.format(Locale.US, "COPY %s -> %s", diffEntry.getOldPath(), diffEntry.getNewPath()));
                    break;
                case RENAME:
                    if (DEBUG_LOG) System.out.println(String.format(Locale.US, "RENAME %s -> %s", diffEntry.getOldPath(), diffEntry.getNewPath()));
                    addAuthorsFromBlame(affectedReviewers, git.blame().setFilePath(diffEntry.getOldPath()).call(), emailsToIgnore);
                    break;
                case MODIFY:
                    if (DEBUG_LOG) System.out.println(String.format(Locale.US, "MODIFY %s", diffEntry.getNewPath()));
                    addAuthorsFromBlame(affectedReviewers, git.blame().setFilePath(diffEntry.getOldPath()).call(), emailsToIgnore);
                    break;
            }
        }
        //sorting by weight
        List<Map.Entry<String, Integer>> listOfReviewers = new ArrayList<Map.Entry<String, Integer>>(affectedReviewers.entrySet());
        Collections.sort(listOfReviewers, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                return o2.getValue() - o1.getValue();
            }
        });
        System.out.println("");
        System.out.println("*** List of possible reviewers ***");
        for (Map.Entry<String, Integer> entry : listOfReviewers) {
            System.out.println(String.format(Locale.US, "%s with weight %d", entry.getKey(), entry.getValue()));
        }
        System.out.println("----------------------");
        int reviewers = DEFAULT_REVIEWERS_COUNT;
        for (Map.Entry<String, Integer> entry : listOfReviewers) {
            final String possibleGheHandle = "@"+entry.getKey().substring(0, entry.getKey().indexOf("@"));
            System.out.print(possibleGheHandle+" ");
            if (0 == --reviewers) break;
        }
        System.out.println("");
        System.out.println("----------------------");
        System.out.println("");
    }

    private static void addAuthorsFromBlame(Map<String, Integer> emailsOfReviewers, BlameResult blameResult, List<String> authorsToIgnore) {
        final int lines = blameResult.getResultContents().size();
        for (int line=0; line<lines; line++) {
            String email = blameResult.getSourceAuthor(line).getEmailAddress();
            if (authorsToIgnore.contains(email)) continue;

            if (emailsOfReviewers.containsKey(email)) {
                emailsOfReviewers.put(email, emailsOfReviewers.get(email)+1);
            } else {
                emailsOfReviewers.put(email, 1);
            }
        }
    }
}
